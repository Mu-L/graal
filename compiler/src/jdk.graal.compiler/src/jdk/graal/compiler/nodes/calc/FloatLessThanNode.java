/*
 * Copyright (c) 2011, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes.calc;

import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_2;

import jdk.graal.compiler.core.common.calc.CanonicalCondition;
import jdk.graal.compiler.core.common.type.FloatStamp;
import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.TriState;

@NodeInfo(shortName = "<", cycles = CYCLES_2)
public final class FloatLessThanNode extends CompareNode {
    public static final NodeClass<FloatLessThanNode> TYPE = NodeClass.create(FloatLessThanNode.class);
    private static final FloatLessThanOp OP = new FloatLessThanOp();

    public FloatLessThanNode(ValueNode x, ValueNode y, boolean unorderedIsTrue) {
        super(TYPE, CanonicalCondition.LT, unorderedIsTrue, x, y);
        Stamp xStamp = x.stamp(NodeView.DEFAULT);
        Stamp yStamp = y.stamp(NodeView.DEFAULT);
        assert xStamp.isFloatStamp() : "expected floating point x value: " + x;
        assert yStamp.isFloatStamp() : "expected floating point y value: " + y;
        assert xStamp.isCompatible(yStamp) : "expected compatible stamps: " + xStamp + " / " + yStamp;
    }

    public static LogicNode create(ValueNode x, ValueNode y, boolean unorderedIsTrue, NodeView view) {
        LogicNode result = tryConstantFoldPrimitive(CanonicalCondition.LT, x, y, unorderedIsTrue, view);
        if (result != null) {
            return result;
        }
        return new FloatLessThanNode(x, y, unorderedIsTrue);
    }

    public static LogicNode create(ConstantReflectionProvider constantReflection, MetaAccessProvider metaAccess, OptionValues options, Integer smallestCompareWidth,
                    ValueNode x, ValueNode y, boolean unorderedIsTrue, NodeView view) {
        LogicNode result = OP.canonical(constantReflection, metaAccess, options, smallestCompareWidth, CanonicalCondition.LT, unorderedIsTrue, x, y, view);
        if (result != null) {
            return result;
        }
        return create(x, y, unorderedIsTrue, view);
    }

    @Override
    public Node canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        NodeView view = NodeView.from(tool);
        ValueNode value = OP.canonical(tool.getConstantReflection(), tool.getMetaAccess(), tool.getOptions(), tool.smallestCompareWidth(), CanonicalCondition.LT, unorderedIsTrue, forX, forY, view);
        if (value != null) {
            return value;
        }
        return super.canonical(tool, forX, forY);
    }

    public static class FloatLessThanOp extends CompareOp {

        @Override
        public LogicNode canonical(ConstantReflectionProvider constantReflection, MetaAccessProvider metaAccess, OptionValues options, Integer smallestCompareWidth, CanonicalCondition condition,
                        boolean unorderedIsTrue, ValueNode forX, ValueNode forY, NodeView view) {
            LogicNode result = super.canonical(constantReflection, metaAccess, options, smallestCompareWidth, condition, unorderedIsTrue, forX, forY, view);
            if (result != null) {
                return result;
            }
            if (GraphUtil.unproxify(forX) == GraphUtil.unproxify(forY)) {
                if (!unorderedIsTrue || (forX.stamp(view) instanceof FloatStamp xStamp && xStamp.isNonNaN() && forY.stamp(view) instanceof FloatStamp yStamp && yStamp.isNonNaN())) {
                    /*
                     * If x is NaN and an unordered result is false, x < x is false. Otherwise, if x
                     * cannot be NaN, x < x is false too.
                     */
                    return LogicConstantNode.contradiction();
                }
            }
            return null;
        }

        @Override
        protected LogicNode duplicateModified(ValueNode newX, ValueNode newY, boolean unorderedIsTrue, NodeView view) {
            if (newX.stamp(NodeView.DEFAULT) instanceof FloatStamp && newY.stamp(NodeView.DEFAULT) instanceof FloatStamp) {
                return FloatLessThanNode.create(newX, newY, unorderedIsTrue, view);
            } else if (newX.stamp(NodeView.DEFAULT) instanceof IntegerStamp && newY.stamp(NodeView.DEFAULT) instanceof IntegerStamp) {
                return IntegerLessThanNode.create(newX, newY, view);
            }
            throw GraalError.shouldNotReachHere(newX.stamp(view) + " " + newY.stamp(view)); // ExcludeFromJacocoGeneratedReport
        }
    }

    @Override
    public Stamp getSucceedingStampForX(boolean negated, Stamp xStamp, Stamp yStamp) {
        return null;
    }

    @Override
    public Stamp getSucceedingStampForY(boolean negated, Stamp xStamp, Stamp yStamp) {
        return null;
    }

    @Override
    public TriState tryFold(Stamp xStampGeneric, Stamp yStampGeneric) {
        return TriState.UNKNOWN;
    }
}
