/*
 * Copyright (c) 2015, 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.replacements.test;

import jdk.graal.compiler.api.directives.GraalDirectives;
import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.word.WordCastNode;
import org.junit.Test;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class PointerTrackingTest extends ReplacementsTest implements Snippets {

    @Test
    public void testTracking() {
        Result result = executeActual(getResolvedJavaMethod("trackingSnippet"), null);
        assertEquals(new Result("OK", null), result);
    }

    public static String trackingSnippet() {
        for (int i = 0; i < 1000; i++) {
            // generate a lot of garbage, trying to get a fresh TLAB
            GraalDirectives.blackhole(new Object());
        }

        // fresh allocation, this should be in TLAB
        Object obj = GraalDirectives.opaque(new Object());

        long trackedBeforeGC = getTrackedPointer(obj);
        long untrackedBeforeGC = getUntrackedPointer(obj);

        int i = 0;
        while (untrackedBeforeGC == getTrackedPointer(obj)) {
            // allocate something to increase likelyhood of GC moving the object
            GraalDirectives.blackhole(new Object());

            System.gc();
            if (i++ > 1000) {
                return "Timeout! Object didn't move after 1000 GCs.";
            }
        }

        long trackedAfterGC = getTrackedPointer(obj);
        long untrackedAfterGC = getUntrackedPointer(obj);

        if (untrackedBeforeGC == untrackedAfterGC) {
            /*
             * The untracked pointer isn't supposed to be updated, so it should be different before
             * and after GC.
             */
            return "untrackedBeforeGC == untrackedAfterGC";
        }
        if (trackedBeforeGC != trackedAfterGC) {
            /*
             * The trackedBeforeGC variable should be updated to the new location by the GC, so it
             * should be equal to trackedAfterGC.
             */
            return "trackedBeforeGC != trackedAfterGC";
        }

        return "OK";
    }

    @Test(expected = GraalError.class)
    public void testVerification() {
        DebugContext debug = getDebugContext();
        try (DebugCloseable _ = debug.disableIntercept(); DebugContext.Scope _ = debug.scope("PointerTrackingTest")) {
            compile(getResolvedJavaMethod("verificationSnippet"), null);
        }
    }

    public static long verificationSnippet(Object obj) {
        long value = getTrackedPointer(obj) * 7 + 3;

        /*
         * Ensure usage before and after GC to force the value to be alive across the safepoint.
         * This should lead to a compiler error, since value can not be tracked in the reference
         * map.
         */
        GraalDirectives.blackhole(value);
        System.gc();
        return value;
    }

    static long getTrackedPointer(@SuppressWarnings("unused") Object obj) {
        throw GraalError.shouldNotReachHere("should be intrinsified");
    }

    static long getUntrackedPointer(@SuppressWarnings("unused") Object obj) {
        throw GraalError.shouldNotReachHere("should be intrinsified");
    }

    @Override
    protected void registerInvocationPlugins(InvocationPlugins invocationPlugins) {
        Registration r = new Registration(invocationPlugins, PointerTrackingTest.class);
        r.register(new InvocationPlugin("getTrackedPointer", Object.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg) {
                WordCastNode objectToTracked = b.add(WordCastNode.objectToTrackedPointer(arg, getReplacements().getWordKind()));
                b.addPush(JavaKind.Long, objectToTracked);
                return true;
            }
        });
        r.register(new InvocationPlugin("getUntrackedPointer", Object.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg) {
                WordCastNode objectToTracked = b.add(WordCastNode.objectToUntrackedPointer(arg, getReplacements().getWordKind()));
                b.addPush(JavaKind.Long, objectToTracked);
                return true;
            }
        });
    }
}
