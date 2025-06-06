/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.snippets;

import org.graalvm.word.Pointer;

import com.oracle.svm.core.hub.DynamicHub;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node.ConstantNodeParameter;
import jdk.graal.compiler.graph.Node.NodeIntrinsic;
import jdk.graal.compiler.nodes.BreakpointNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.extended.LoadHubNode;
import jdk.graal.compiler.nodes.extended.LoadHubOrNullNode;
import jdk.vm.ci.meta.SpeculationLog.SpeculationReason;

public class SubstrateIntrinsics {

    public interface Any {
    }

    @NodeIntrinsic(LoadHubNode.class)
    public static native DynamicHub loadHub(Object object);

    @NodeIntrinsic(LoadHubOrNullNode.class)
    public static native DynamicHub loadHubOrNull(Object object);

    @NodeIntrinsic(value = ForeignCallNode.class)
    public static native void runtimeCall(@ConstantNodeParameter ForeignCallDescriptor descriptor);

    @NodeIntrinsic(value = ForeignCallNode.class)
    public static native void runtimeCall(@ConstantNodeParameter ForeignCallDescriptor descriptor, long actionAndReason, SpeculationReason speculation);

    @NodeIntrinsic(value = ForeignCallNode.class)
    public static native Object runtimeCall(@ConstantNodeParameter ForeignCallDescriptor descriptor, Object object);

    @NodeIntrinsic(value = ForeignCallNode.class)
    public static native void runtimeCall(@ConstantNodeParameter ForeignCallDescriptor descriptor, byte[] message);

    @NodeIntrinsic(value = ForeignCallNode.class)
    public static native void runtimeCall(@ConstantNodeParameter ForeignCallDescriptor descriptor, byte[] message, Object object);

    @NodeIntrinsic(value = ForeignCallNode.class)
    public static native void runtimeCall(@ConstantNodeParameter ForeignCallDescriptor descriptor, Throwable exception, Pointer sp);

    @NodeIntrinsic(value = ForeignCallNode.class)
    public static native void runtimeCall(@ConstantNodeParameter ForeignCallDescriptor descriptor, Throwable exception);

    @NodeIntrinsic(value = ForeignCallNode.class)
    public static native Pointer runtimeCall(@ConstantNodeParameter ForeignCallDescriptor descriptor, Pointer returnAddress);

    @NodeIntrinsic(BreakpointNode.class)
    public static native void breakpoint();

    @NodeIntrinsic(value = ForeignCallNode.class)
    public static native Object runtimeCall(@ConstantNodeParameter ForeignCallDescriptor descriptor, String str);

}
