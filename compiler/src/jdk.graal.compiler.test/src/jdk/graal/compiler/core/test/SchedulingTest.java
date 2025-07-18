/*
 * Copyright (c) 2015, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.core.test;

import java.util.List;

import org.junit.Test;

import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeMap;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.LoopExitNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.AllowAssumptions;
import jdk.graal.compiler.nodes.StructuredGraph.ScheduleResult;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.calc.BinaryArithmeticNode;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.phases.schedule.SchedulePhase;
import jdk.graal.compiler.phases.schedule.SchedulePhase.SchedulingStrategy;

public class SchedulingTest extends GraphScheduleTest {

    public static int testValueProxyInputsSnippet(int s) {
        int i = 0;
        while (true) {
            i++;
            int v = i - s * 2;
            if (i == s) {
                return v;
            }
        }
    }

    @Test
    public void testValueProxyInputs() {
        StructuredGraph graph = parseEager("testValueProxyInputsSnippet", AllowAssumptions.YES);
        for (FrameState fs : graph.getNodes().filter(FrameState.class).snapshot()) {
            fs.replaceAtUsages(null);
            GraphUtil.killWithUnusedFloatingInputs(fs);
        }
        graph.clearAllStateAfterForTestingOnly();
        SchedulePhase schedulePhase = new SchedulePhase(SchedulingStrategy.LATEST);
        schedulePhase.apply(graph, getDefaultHighTierContext());
        ScheduleResult schedule = graph.getLastSchedule();
        NodeMap<HIRBlock> nodeToBlock = schedule.getNodeToBlockMap();
        assertTrue(graph.getNodes().filter(LoopExitNode.class).count() == 1);
        LoopExitNode loopExit = graph.getNodes().filter(LoopExitNode.class).first();
        List<Node> list = schedule.nodesFor(nodeToBlock.get(loopExit));
        for (BinaryArithmeticNode<?> node : graph.getNodes().filter(BinaryArithmeticNode.class)) {
            if (!(node instanceof AddNode)) {
                assertTrue(node.toString(), nodeToBlock.get(node) == nodeToBlock.get(loopExit));
                assertTrue(list.indexOf(node) + " < " + list.indexOf(loopExit) + ", " + node + ", " + loopExit, list.indexOf(node) < list.indexOf(loopExit));
            }
        }
    }
}
