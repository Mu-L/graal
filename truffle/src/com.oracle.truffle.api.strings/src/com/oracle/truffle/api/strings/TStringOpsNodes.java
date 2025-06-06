/*
 * Copyright (c) 2021, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.strings;

import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;
import com.oracle.truffle.api.strings.TruffleString.CompactionLevel;

final class TStringOpsNodes {

    /**
     * Maximum possible combinations of two different stride values; possible stride values are
     * {@code [0, 1, 2]}. We can use this large limit here because all nodes in
     * {@link TStringOpsNodes} just exist to dispatch to various intrinsic stubs that require
     * constant stride values.
     */
    static final String LIMIT_STRIDE = "9";

    abstract static class IndexOfAnyCharUTF16Node extends AbstractInternalNode {

        abstract int execute(Node node, AbstractTruffleString a, byte[] arrayA, long offsetA, int fromIndex, int maxIndex, char[] values);

        @Specialization(guards = {"isStride0(a)", "values.length == 1"})
        int stride0(@SuppressWarnings("unused") AbstractTruffleString a, byte[] arrayA, long offsetA, int fromIndex, int maxIndex, char[] values) {
            return TStringOps.indexOfAnyChar(this, arrayA, offsetA, 0, fromIndex, maxIndex, values);
        }

        @Specialization(guards = {"isStride0(a)", "values.length > 1"})
        int stride0MultiValue(@SuppressWarnings("unused") AbstractTruffleString a, byte[] arrayA, long offsetA, int fromIndex, int maxIndex, char[] values) {
            return TStringOps.indexOfAnyChar(this, arrayA, offsetA, 0, fromIndex, maxIndex, removeValuesGreaterThan(this, values, 0xff));
        }

        @Specialization(guards = "isStride1(a)")
        int stride1(@SuppressWarnings("unused") AbstractTruffleString a, byte[] arrayA, long offsetA, int fromIndex, int maxIndex, char[] values) {
            return TStringOps.indexOfAnyChar(this, arrayA, offsetA, 1, fromIndex, maxIndex, values);
        }
    }

    abstract static class IndexOfAnyIntNode extends AbstractInternalNode {

        abstract int execute(Node node, AbstractTruffleString a, byte[] arrayA, long offsetA, int fromIndex, int maxIndex, int[] values);

        @Specialization(guards = {"isStride0(a)", "values.length == 1"})
        int stride0(@SuppressWarnings("unused") AbstractTruffleString a, byte[] arrayA, long offsetA, int fromIndex, int maxIndex, int[] values) {
            return TStringOps.indexOfAnyInt(this, arrayA, offsetA, 0, fromIndex, maxIndex, values);
        }

        @Specialization(guards = {"isStride0(a)", "values.length > 1"})
        int stride0MultiValue(@SuppressWarnings("unused") AbstractTruffleString a, byte[] arrayA, long offsetA, int fromIndex, int maxIndex, int[] values) {
            return TStringOps.indexOfAnyInt(this, arrayA, offsetA, 0, fromIndex, maxIndex, removeValuesGreaterThan(this, values, 0xff));
        }

        @Specialization(guards = {"isStride1(a)", "values.length == 1"})
        int stride1(@SuppressWarnings("unused") AbstractTruffleString a, byte[] arrayA, long offsetA, int fromIndex, int maxIndex, int[] values) {
            return TStringOps.indexOfAnyInt(this, arrayA, offsetA, 1, fromIndex, maxIndex, values);
        }

        @Specialization(guards = {"isStride1(a)", "values.length > 1"})
        int stride1MultiValue(@SuppressWarnings("unused") AbstractTruffleString a, byte[] arrayA, long offsetA, int fromIndex, int maxIndex, int[] values) {
            return TStringOps.indexOfAnyInt(this, arrayA, offsetA, 1, fromIndex, maxIndex, removeValuesGreaterThan(this, values, 0xffff));
        }

        @Specialization(guards = "isStride2(a)")
        int stride2(@SuppressWarnings("unused") AbstractTruffleString a, byte[] arrayA, long offsetA, int fromIndex, int maxIndex, int[] values) {
            return TStringOps.indexOfAnyInt(this, arrayA, offsetA, 2, fromIndex, maxIndex, values);
        }
    }

    abstract static class RawIndexOfCodePointNode extends AbstractInternalNode {

        abstract int execute(Node node, AbstractTruffleString a, byte[] arrayA, long offsetA, int codepoint, int fromIndex, int toIndex);

        @SuppressWarnings("unused")
        @Specialization(guards = {"compaction == cachedCompaction"}, limit = Stride.STRIDE_CACHE_LIMIT, unroll = Stride.STRIDE_UNROLL)
        static int cached(Node node, AbstractTruffleString a, byte[] arrayA, long offsetA, int codepoint, int fromIndex, int toIndex,
                        @Bind("fromStride(a.stride())") CompactionLevel compaction,
                        @Cached("compaction") CompactionLevel cachedCompaction) {
            return TStringOps.indexOfCodePointWithStride(node, arrayA, offsetA, cachedCompaction.getStride(), fromIndex, toIndex, codepoint);
        }
    }

    abstract static class RawLastIndexOfCodePointNode extends AbstractInternalNode {

        abstract int execute(Node node, AbstractTruffleString a, byte[] arrayA, long offsetA, int codepoint, int fromIndex, int toIndex);

        @SuppressWarnings("unused")
        @Specialization(guards = {"compaction == cachedCompaction"}, limit = Stride.STRIDE_CACHE_LIMIT, unroll = Stride.STRIDE_UNROLL)
        static int cached(Node node, AbstractTruffleString a, byte[] arrayA, long offsetA, int codepoint, int fromIndex, int toIndex,
                        @Bind("fromStride(a.stride())") CompactionLevel compaction,
                        @Cached("compaction") CompactionLevel cachedCompaction) {
            return TStringOps.lastIndexOfCodePointWithOrMaskWithStride(node, arrayA, offsetA, cachedCompaction.getStride(), fromIndex, toIndex, codepoint, 0);
        }
    }

    abstract static class RawIndexOfStringNode extends AbstractInternalNode {

        abstract int execute(Node node,
                        AbstractTruffleString a, byte[] arrayA, long offsetA,
                        AbstractTruffleString b, byte[] arrayB, long offsetB, int fromIndex, int toIndex, byte[] mask);

        @SuppressWarnings("unused")
        @Specialization(guards = {"compactionA == cachedCompactionA", "compactionB == cachedCompactionB"}, limit = LIMIT_STRIDE)
        static int doCached(Node node,
                        AbstractTruffleString a, byte[] arrayA, long offsetA,
                        AbstractTruffleString b, byte[] arrayB, long offsetB, int fromIndex, int toIndex, byte[] mask,
                        @Bind("fromStride(a.stride())") CompactionLevel compactionA,
                        @Cached("compactionA") CompactionLevel cachedCompactionA,
                        @Bind("fromStride(b.stride())") CompactionLevel compactionB,
                        @Cached("compactionB") CompactionLevel cachedCompactionB,
                        @Cached InlinedConditionProfile oneLength) {
            int strideB = cachedCompactionB.getStride();
            int strideA = cachedCompactionA.getStride();
            return runIndexOf(node, arrayA, offsetA, a.length(), strideA, arrayB, offsetB, b.length(), strideB, fromIndex, toIndex, mask, oneLength);
        }

        static int runIndexOf(Node node,
                        byte[] arrayA, long offsetA, int lengthA, int strideA,
                        byte[] arrayB, long offsetB, int lengthB, int strideB, int fromIndex, int toIndex, byte[] mask,
                        InlinedConditionProfile oneLength) {
            if (oneLength.profile(node, lengthB == 1)) {
                final int b0 = TStringOps.readValue(arrayB, offsetB, lengthB, strideB, 0);
                final int mask0 = mask == null ? 0 : TStringOps.readFromByteArray(mask, strideB, 0);
                return TStringOps.indexOfCodePointWithOrMaskWithStride(node, arrayA, offsetA, strideA, fromIndex, toIndex, b0, mask0);
            } else {
                return TStringOps.indexOfStringWithOrMaskWithStride(node,
                                arrayA, offsetA, lengthA, strideA,
                                arrayB, offsetB, lengthB, strideB, fromIndex, toIndex, mask);
            }
        }
    }

    abstract static class RawLastIndexOfStringNode extends AbstractInternalNode {

        abstract int execute(Node node,
                        AbstractTruffleString a, byte[] arrayA, long offsetA,
                        AbstractTruffleString b, byte[] arrayB, long offsetB, int fromIndex, int toIndex, byte[] mask);

        @SuppressWarnings("unused")
        @Specialization(guards = {"compactionA == cachedCompactionA", "compactionB == cachedCompactionB"}, limit = LIMIT_STRIDE)
        static int cachedLen1(Node node,
                        AbstractTruffleString a, byte[] arrayA, long offsetA,
                        AbstractTruffleString b, byte[] arrayB, long offsetB, int fromIndex, int toIndex, byte[] mask,
                        @Bind("fromStride(a.stride())") CompactionLevel compactionA,
                        @Cached("compactionA") CompactionLevel cachedCompactionA,
                        @Bind("fromStride(b.stride())") CompactionLevel compactionB,
                        @Cached("compactionB") CompactionLevel cachedCompactionB,
                        @Cached InlinedConditionProfile oneLength) {
            int strideA = cachedCompactionA.getStride();
            int strideB = cachedCompactionB.getStride();
            return runIndexOf(node, arrayA, offsetA, a.length(), strideA, arrayB, offsetB, b.length(), strideB, fromIndex, toIndex, mask, oneLength);
        }

        static int runIndexOf(Node node,
                        byte[] arrayA, long offsetA, int lengthA, int strideA,
                        byte[] arrayB, long offsetB, int lengthB, int strideB, int fromIndex, int toIndex, byte[] mask,
                        InlinedConditionProfile oneLength) {
            if (oneLength.profile(node, lengthB == 1)) {
                final int b0 = TStringOps.readValue(arrayB, offsetB, lengthB, strideB, 0);
                final int mask0 = mask == null ? 0 : TStringOps.readFromByteArray(mask, strideB, 0);
                return TStringOps.lastIndexOfCodePointWithOrMaskWithStride(node, arrayA, offsetA, strideA, fromIndex, toIndex, b0, mask0);
            } else {
                return TStringOps.lastIndexOfStringWithOrMaskWithStride(node,
                                arrayA, offsetA, lengthA, strideA,
                                arrayB, offsetB, lengthB, strideB, fromIndex, toIndex, mask);
            }
        }
    }

    static int memcmp(Node location,
                    AbstractTruffleString a, byte[] arrayA, long offsetA,
                    AbstractTruffleString b, byte[] arrayB, long offsetB) {
        int cmp = TStringOps.memcmpWithStride(location, a, arrayA, offsetA, a.stride(), b, arrayB, offsetB, b.stride(), Math.min(a.length(), b.length()));
        return memCmpTail(cmp, a.length(), b.length());
    }

    static int memcmpBytes(Node location,
                    AbstractTruffleString a, byte[] arrayA, long offsetA,
                    AbstractTruffleString b, byte[] arrayB, long offsetB) {
        int cmp = TStringOps.memcmpBytesWithStride(location, a, arrayA, offsetA, a.stride(), b, arrayB, offsetB, b.stride(), Math.min(a.length(), b.length()));
        return memCmpTail(cmp, a.length(), b.length());
    }

    static int memCmpTail(int cmp, int lengthA, int lengthB) {
        return cmp == 0 ? lengthA - lengthB : cmp;
    }

    @SuppressWarnings("unused")
    abstract static class CalculateHashCodeNode extends AbstractInternalNode {

        abstract int execute(Node node, AbstractTruffleString a, byte[] arrayA, long offsetA);

        @Specialization(guards = "compaction == cachedCompaction", limit = Stride.STRIDE_CACHE_LIMIT, unroll = Stride.STRIDE_UNROLL)
        static int cached(Node node, AbstractTruffleString a, byte[] arrayA, long offsetA,
                        @Bind("fromStride(a.stride())") CompactionLevel compaction,
                        @Cached("compaction") CompactionLevel cachedCompaction) {
            return TStringOps.hashCodeWithStride(node, a, arrayA, offsetA, cachedCompaction.getStride());
        }
    }

    private static char[] removeValuesGreaterThan(Node location, char[] values, int max) {
        int n = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] <= max) {
                n++;
            }
            TStringConstants.truffleSafePointPoll(location, i + 1);
        }
        if (n == values.length) {
            return values;
        }
        final char[] clampedValues = new char[n];
        n = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] <= max) {
                clampedValues[n++] = values[i];
            }
            TStringConstants.truffleSafePointPoll(location, i + 1);
        }
        return clampedValues;
    }

    private static int[] removeValuesGreaterThan(Node location, int[] values, int max) {
        int n = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] <= max) {
                n++;
            }
            TStringConstants.truffleSafePointPoll(location, i + 1);
        }
        if (n == values.length) {
            return values;
        }
        final int[] clampedValues = new int[n];
        n = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] <= max) {
                clampedValues[n++] = values[i];
            }
            TStringConstants.truffleSafePointPoll(location, i + 1);
        }
        return clampedValues;
    }

}
