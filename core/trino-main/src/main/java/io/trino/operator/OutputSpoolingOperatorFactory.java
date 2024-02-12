/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.operator;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.units.Duration;
import io.trino.client.spooling.DataAttributes;
import io.trino.memory.context.LocalMemoryContext;
import io.trino.operator.OperationTimer.OperationTiming;
import io.trino.server.protocol.OutputColumn;
import io.trino.server.protocol.spooling.QueryDataEncoder;
import io.trino.server.protocol.spooling.SpooledBlock;
import io.trino.server.protocol.spooling.SpoolingManagerBridge;
import io.trino.spi.Mergeable;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.protocol.SpooledSegmentHandle;
import io.trino.spi.protocol.SpoolingContext;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.OutputNode;
import io.trino.sql.planner.plan.PlanNodeId;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static io.airlift.units.Duration.succinctDuration;
import static io.trino.client.spooling.DataAttribute.ROWS_COUNT;
import static io.trino.client.spooling.DataAttribute.SEGMENT_SIZE;
import static io.trino.operator.OutputSpoolingOperatorFactory.OutputSpoolingOperator.State.FINISHED;
import static io.trino.operator.OutputSpoolingOperatorFactory.OutputSpoolingOperator.State.HAS_LAST_OUTPUT;
import static io.trino.operator.OutputSpoolingOperatorFactory.OutputSpoolingOperator.State.HAS_OUTPUT;
import static io.trino.operator.OutputSpoolingOperatorFactory.OutputSpoolingOperator.State.NEEDS_INPUT;
import static io.trino.server.protocol.spooling.SpooledBlock.SPOOLING_METADATA_SYMBOL;
import static io.trino.server.protocol.spooling.SpooledBlock.SPOOLING_METADATA_TYPE;
import static io.trino.server.protocol.spooling.SpooledBlock.createNonSpooledPage;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class OutputSpoolingOperatorFactory
        implements OperatorFactory
{
    private final int operatorId;
    private final PlanNodeId planNodeId;
    private final Map<Symbol, Integer> operatorLayout;
    private final SpoolingManagerBridge spoolingManager;
    private final QueryDataEncoder queryDataEncoder;
    private boolean closed;

    public OutputSpoolingOperatorFactory(int operatorId, PlanNodeId planNodeId, Map<Symbol, Integer> operatorLayout, QueryDataEncoder queryDataEncoder, SpoolingManagerBridge spoolingManager)
    {
        this.operatorId = operatorId;
        this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
        this.operatorLayout = ImmutableMap.copyOf(requireNonNull(operatorLayout, "layout is null"));
        this.queryDataEncoder = requireNonNull(queryDataEncoder, "queryDataEncoder is null");
        this.spoolingManager = requireNonNull(spoolingManager, "spoolingManager is null");
    }

    public static List<OutputColumn> spooledOutputLayout(OutputNode outputNode, Map<Symbol, Integer> layout)
    {
        List<String> columnNames = outputNode.getColumnNames();
        List<Symbol> outputSymbols = outputNode.getOutputSymbols();

        ImmutableList.Builder<OutputColumn> outputColumnBuilder = ImmutableList.builderWithExpectedSize(outputNode.getColumnNames().size());
        for (int i = 0; i < columnNames.size(); i++) {
            if (outputSymbols.get(i).type().equals(SPOOLING_METADATA_TYPE)) {
                continue;
            }
            outputColumnBuilder.add(new OutputColumn(layout.get(outputSymbols.get(i)), columnNames.get(i), outputSymbols.get(i).type()));
        }
        return outputColumnBuilder.build();
    }

    public static Map<Symbol, Integer> layoutUnionWithSpooledMetadata(Map<Symbol, Integer> layout)
    {
        int maxChannelId = layout.values()
                .stream()
                .max(Integer::compareTo)
                .orElseThrow();

        verify(maxChannelId + 1 == layout.size(), "Max channel id %s is not equal to layout size: %s", maxChannelId, layout.size());
        return ImmutableMap.<Symbol, Integer>builderWithExpectedSize(layout.size() + 1)
                .putAll(layout)
                .put(SPOOLING_METADATA_SYMBOL, maxChannelId + 1)
                .buildOrThrow();
    }

    @Override
    public Operator createOperator(DriverContext driverContext)
    {
        checkState(!closed, "Factory is already closed");
        OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, OutputSpoolingOperator.class.getSimpleName());
        return new OutputSpoolingOperator(operatorContext, queryDataEncoder, spoolingManager, operatorLayout);
    }

    @Override
    public void noMoreOperators()
    {
        closed = true;
    }

    @Override
    public OperatorFactory duplicate()
    {
        return new OutputSpoolingOperatorFactory(operatorId, planNodeId, operatorLayout, queryDataEncoder, spoolingManager);
    }

    static class OutputSpoolingOperator
            implements Operator
    {
        private final OutputSpoolingController controller;

        enum State
        {
            NEEDS_INPUT, // output is not ready
            HAS_OUTPUT, // output page ready
            HAS_LAST_OUTPUT, // last output page ready
            FINISHED // no more pages will be ever produced
        }

        private OutputSpoolingOperator.State state = NEEDS_INPUT;
        private final OperatorContext operatorContext;
        private final LocalMemoryContext userMemoryContext;
        private final QueryDataEncoder queryDataEncoder;
        private final SpoolingManagerBridge spoolingManager;
        private final Map<Symbol, Integer> layout;
        private final PageBuffer buffer = PageBuffer.create();
        private final Block[] emptyBlocks;

        private final OperationTiming encodingTiming = new OperationTiming();
        private final OperationTiming spoolingTiming = new OperationTiming();
        private Page outputPage;

        public OutputSpoolingOperator(OperatorContext operatorContext, QueryDataEncoder queryDataEncoder, SpoolingManagerBridge spoolingManager, Map<Symbol, Integer> layout)
        {
            this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
            this.controller = new OutputSpoolingController(
                    spoolingManager.useInlineSegments(),
                    20,
                    1024,
                    spoolingManager.initialSegmentSize(),
                    spoolingManager.maximumSegmentSize());
            this.userMemoryContext = operatorContext.localUserMemoryContext();
            this.queryDataEncoder = requireNonNull(queryDataEncoder, "queryDataEncoder is null");
            this.spoolingManager = requireNonNull(spoolingManager, "spoolingManager is null");
            this.layout = requireNonNull(layout, "layout is null");
            this.emptyBlocks = emptyBlocks(layout);

            operatorContext.setInfoSupplier(new OutputSpoolingInfoSupplier(encodingTiming, spoolingTiming, controller));
        }

        @Override
        public OperatorContext getOperatorContext()
        {
            return operatorContext;
        }

        @Override
        public boolean needsInput()
        {
            return state == NEEDS_INPUT;
        }

        @Override
        public void addInput(Page page)
        {
            checkState(needsInput(), "Operator is already finishing");
            requireNonNull(page, "page is null");

            outputPage = switch (controller.getNextMode(page)) {
                case SPOOL -> {
                    buffer.add(page);
                    yield outputBuffer(false);
                }
                case BUFFER -> {
                    buffer.add(page);
                    yield null;
                }
                case INLINE -> createNonSpooledPage(page);
            };

            if (outputPage != null) {
                state = HAS_OUTPUT;
            }
        }

        @Override
        public Page getOutput()
        {
            if (state != HAS_OUTPUT && state != HAS_LAST_OUTPUT) {
                return null;
            }

            Page toReturn = outputPage;
            outputPage = null;
            state = state == HAS_LAST_OUTPUT ? FINISHED : NEEDS_INPUT;
            return toReturn;
        }

        @Override
        public void finish()
        {
            if (state == NEEDS_INPUT) {
                outputPage = outputBuffer(true);
                if (outputPage != null) {
                    state = HAS_LAST_OUTPUT;
                }
                else {
                    state = FINISHED;
                }
            }
        }

        @Override
        public boolean isFinished()
        {
            return state == FINISHED;
        }

        private Page outputBuffer(boolean finished)
        {
            if (buffer.isEmpty()) {
                return null;
            }

            synchronized (buffer) {
                return spool(buffer.removeAll(), finished);
            }
        }

        private Page spool(List<Page> pages, boolean finished)
        {
            long rows = reduce(pages, Page::getPositionCount);
            if (finished) {
                long size = reduce(pages, Page::getSizeInBytes);
                controller.recordSpooled(rows, size); // final buffer
            }

            SpoolingContext spoolingContext = new SpoolingContext(operatorContext.getDriverContext().getSession().getQueryId(), rows);
            SpooledSegmentHandle segmentHandle = spoolingManager.create(spoolingContext);

            try (OutputStream output = spoolingManager.createOutputStream(segmentHandle);
                    ByteArrayOutputStream bufferedOutput = new ByteArrayOutputStream(toIntExact(controller.getCurrentSpooledSegmentTarget()))) {
                OperationTimer encodingTimer = new OperationTimer(true);
                DataAttributes attributes = queryDataEncoder.encodeTo(bufferedOutput, pages)
                        .toBuilder()
                        .set(ROWS_COUNT, rows)
                        .build();
                encodingTimer.end(encodingTiming);
                OperationTimer spoolingTimer = new OperationTimer(true);
                output.write(bufferedOutput.toByteArray());
                spoolingTimer.end(spoolingTiming);
                controller.recordEncoded(attributes.get(SEGMENT_SIZE, Integer.class));
                return emptySingleRowPage(layout, new SpooledBlock(spoolingManager.location(segmentHandle), attributes).serialize());
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
            finally {
                userMemoryContext.setBytes(0);
            }
        }

        private Page emptySingleRowPage(Map<Symbol, Integer> layout, Block block)
        {
            Block[] blocks = emptyBlocks;
            blocks[layout.get(SPOOLING_METADATA_SYMBOL)] = block;
            return new Page(blocks);
        }

        static long reduce(List<Page> page, ToLongFunction<Page> reduce)
        {
            return page.stream()
                    .mapToLong(reduce)
                    .sum();
        }

        private static Block[] emptyBlocks(Map<Symbol, Integer> layout)
        {
            Block[] blocks = new Block[layout.size()];
            for (Map.Entry<Symbol, Integer> entry : layout.entrySet()) {
                if (!entry.getKey().type().equals(SPOOLING_METADATA_TYPE)) {
                    blocks[entry.getValue()] = entry.getKey().type().createBlockBuilder(null, 1).appendNull().build();
                }
            }

            return blocks;
        }
    }

    private static class PageBuffer
    {
        private final List<Page> buffer;

        private PageBuffer(List<Page> buffer)
        {
            this.buffer = requireNonNull(buffer, "buffer is null");
        }

        public static PageBuffer create()
        {
            return new PageBuffer(new ArrayList<>());
        }

        public void add(Page page)
        {
            buffer.add(page);
        }

        public boolean isEmpty()
        {
            return buffer.isEmpty();
        }

        public List<Page> removeAll()
        {
            List<Page> pages;
            synchronized (buffer) {
                pages = ImmutableList.copyOf(buffer);
                buffer.clear();
            }
            return pages;
        }
    }

    private record OutputSpoolingInfoSupplier(
            OperationTiming encodingTiming,
            OperationTiming spoolingTiming,
            OutputSpoolingController controller)
            implements Supplier<OutputSpoolingInfo>
    {
        private OutputSpoolingInfoSupplier
        {
            requireNonNull(encodingTiming, "encodingTiming is null");
            requireNonNull(spoolingTiming, "spoolingTiming is null");
            requireNonNull(controller, "controller is null");
        }

        @Override
        public OutputSpoolingInfo get()
        {
            return new OutputSpoolingInfo(
                    succinctDuration(encodingTiming.getWallNanos(), NANOSECONDS),
                    succinctDuration(encodingTiming.getCpuNanos(), NANOSECONDS),
                    succinctDuration(spoolingTiming.getWallNanos(), NANOSECONDS),
                    succinctDuration(spoolingTiming.getCpuNanos(), NANOSECONDS),
                    controller.getInlinedPages(),
                    controller.getInlinedPositions(),
                    controller.getInlinedRawBytes(),
                    controller.getSpooledPages(),
                    controller.getSpooledPositions(),
                    controller.getSpooledRawBytes(),
                    controller.getSpooledEncodedBytes());
        }
    }

    public record OutputSpoolingInfo(
            Duration encodingWallTime,
            Duration encodingCpuTime,
            Duration spoolingWallTime,
            Duration spoolingCpuTime,
            long inlinedPages,
            long inlinedPositions,
            long inlinedRawBytes,
            long spooledPages,
            long spooledPositions,
            long spooledRawBytes,
            long spooledEncodedBytes)
            implements Mergeable<OutputSpoolingInfo>, OperatorInfo
    {
        public OutputSpoolingInfo
        {
            requireNonNull(encodingWallTime, "encodingWallTime is null");
            requireNonNull(encodingCpuTime, "encodingCpuTime is null");
        }

        @Override
        public OutputSpoolingInfo mergeWith(OutputSpoolingInfo other)
        {
            return new OutputSpoolingInfo(
                    succinctDuration(encodingWallTime.toMillis() + other.encodingWallTime().toMillis(), MILLISECONDS),
                    succinctDuration(encodingCpuTime.toMillis() + other.encodingCpuTime().toMillis(), MILLISECONDS),
                    succinctDuration(spoolingWallTime.toMillis() + other.spoolingWallTime().toMillis(), MILLISECONDS),
                    succinctDuration(spoolingCpuTime.toMillis() + other.spoolingCpuTime().toMillis(), MILLISECONDS),
                    inlinedPages + other.inlinedPages(),
                    inlinedPositions + other.inlinedPositions,
                    inlinedRawBytes + other.inlinedRawBytes,
                    spooledPages + other.spooledPages,
                    spooledPositions + other.spooledPositions,
                    spooledRawBytes + other.spooledRawBytes,
                    spooledEncodedBytes + other.spooledEncodedBytes);
        }

        @JsonProperty
        public double getEncodedToRawBytesRatio()
        {
            return 1.0 * spooledEncodedBytes / spooledRawBytes;
        }

        @Override
        public boolean isFinal()
        {
            return true;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("encodingWallTime", encodingWallTime)
                    .add("encodingCpuTime", encodingCpuTime)
                    .add("spoolingWallTime", spoolingWallTime)
                    .add("spoolingCpuTime", spoolingCpuTime)
                    .add("inlinedPages", inlinedPages)
                    .add("inlinedPositions", inlinedPositions)
                    .add("inlinedRawBytes", inlinedRawBytes)
                    .add("spooledPages", spooledPages)
                    .add("spooledPositions", spooledPositions)
                    .add("spooledRawBytes", spooledRawBytes)
                    .add("spooledEncodedBytes", spooledEncodedBytes)
                    .add("encodedToRawBytesRatio", getEncodedToRawBytesRatio())
                    .toString();
        }
    }
}
