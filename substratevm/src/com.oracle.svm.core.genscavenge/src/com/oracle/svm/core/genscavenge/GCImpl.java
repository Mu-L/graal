/*
 * Copyright (c) 2013, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.genscavenge;

import static com.oracle.svm.core.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;
import static com.oracle.svm.core.genscavenge.HeapVerifier.Occasion.After;
import static com.oracle.svm.core.genscavenge.HeapVerifier.Occasion.Before;
import static com.oracle.svm.core.genscavenge.HeapVerifier.Occasion.During;

import java.lang.ref.Reference;

import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.struct.RawField;
import org.graalvm.nativeimage.c.struct.RawStructure;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.Isolates;
import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.RuntimeAssertionsSupport;
import com.oracle.svm.core.SubstrateGCOptions;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.c.NonmovableArray;
import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoAccess;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.code.RuntimeCodeInfoAccess;
import com.oracle.svm.core.code.RuntimeCodeInfoMemory;
import com.oracle.svm.core.deopt.DeoptimizationSlotPacking;
import com.oracle.svm.core.deopt.DeoptimizedFrame;
import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.genscavenge.AlignedHeapChunk.AlignedHeader;
import com.oracle.svm.core.genscavenge.BasicCollectionPolicies.NeverCollect;
import com.oracle.svm.core.genscavenge.HeapAccounting.HeapSizes;
import com.oracle.svm.core.genscavenge.HeapChunk.Header;
import com.oracle.svm.core.genscavenge.UnalignedHeapChunk.UnalignedHeader;
import com.oracle.svm.core.genscavenge.remset.RememberedSet;
import com.oracle.svm.core.graal.RuntimeCompilation;
import com.oracle.svm.core.heap.AbstractPinnedObjectSupport;
import com.oracle.svm.core.heap.AbstractPinnedObjectSupport.PinnedObjectImpl;
import com.oracle.svm.core.heap.CodeReferenceMapDecoder;
import com.oracle.svm.core.heap.GC;
import com.oracle.svm.core.heap.GCCause;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.heap.NoAllocationVerifier;
import com.oracle.svm.core.heap.ObjectReferenceVisitor;
import com.oracle.svm.core.heap.ObjectVisitor;
import com.oracle.svm.core.heap.OutOfMemoryUtil;
import com.oracle.svm.core.heap.PhysicalMemory;
import com.oracle.svm.core.heap.ReferenceHandler;
import com.oracle.svm.core.heap.ReferenceHandlerThread;
import com.oracle.svm.core.heap.ReferenceMapIndex;
import com.oracle.svm.core.heap.RestrictHeapAccess;
import com.oracle.svm.core.heap.RuntimeCodeCacheCleaner;
import com.oracle.svm.core.heap.SuspendSerialGCMaxHeapSize;
import com.oracle.svm.core.heap.UninterruptibleObjectReferenceVisitor;
import com.oracle.svm.core.heap.UninterruptibleObjectVisitor;
import com.oracle.svm.core.heap.VMOperationInfos;
import com.oracle.svm.core.interpreter.InterpreterSupport;
import com.oracle.svm.core.jdk.RuntimeSupport;
import com.oracle.svm.core.jfr.JfrGCWhen;
import com.oracle.svm.core.jfr.JfrTicks;
import com.oracle.svm.core.jfr.events.AllocationRequiringGCEvent;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.os.ChunkBasedCommittedMemoryProvider;
import com.oracle.svm.core.snippets.ImplicitExceptions;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.stack.JavaFrame;
import com.oracle.svm.core.stack.JavaFrames;
import com.oracle.svm.core.stack.JavaStackWalk;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.thread.JavaVMOperation;
import com.oracle.svm.core.thread.NativeVMOperation;
import com.oracle.svm.core.thread.NativeVMOperationData;
import com.oracle.svm.core.thread.PlatformThreads;
import com.oracle.svm.core.thread.VMOperation;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.core.threadlocal.VMThreadLocalSupport;
import com.oracle.svm.core.util.TimeUtils;
import com.oracle.svm.core.util.Timer;
import com.oracle.svm.core.util.VMError;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.word.Word;

/**
 * Garbage collector (incremental or complete) for {@link HeapImpl}.
 */
public final class GCImpl implements GC {
    private static final long K = 1024;
    static final long M = K * K;

    private final GreyToBlackObjRefVisitor greyToBlackObjRefVisitor = new GreyToBlackObjRefVisitor();
    private final GreyToBlackObjectVisitor greyToBlackObjectVisitor = new GreyToBlackObjectVisitor(greyToBlackObjRefVisitor);
    private final RuntimeCodeCacheWalker runtimeCodeCacheWalker = new RuntimeCodeCacheWalker(greyToBlackObjRefVisitor);
    private final RuntimeCodeCacheCleaner runtimeCodeCacheCleaner = new RuntimeCodeCacheCleaner();

    private final GCAccounting accounting = new GCAccounting();
    private final Timers timers = new Timers();

    private final CollectionVMOperation collectOperation = new CollectionVMOperation();
    private final ChunkReleaser chunkReleaser = new ChunkReleaser();

    private final CollectionPolicy policy;
    private boolean completeCollection = false;
    private UnsignedWord collectionEpoch = Word.zero();
    private long lastWholeHeapExaminedNanos = -1;

    @Platforms(Platform.HOSTED_ONLY.class)
    GCImpl() {
        this.policy = CollectionPolicy.getInitialPolicy();
        RuntimeSupport.getRuntimeSupport().addShutdownHook(isFirstIsolate -> printGCSummary());
    }

    @Override
    public String getName() {
        if (SubstrateOptions.useEpsilonGC()) {
            return "Epsilon GC";
        } else {
            return "Serial GC";
        }
    }

    @Override
    public String getDefaultMaxHeapSize() {
        return String.format("%s%% of RAM", SerialAndEpsilonGCOptions.MaximumHeapSizePercent.getValue());
    }

    @Override
    public void collect(GCCause cause) {
        collect(cause, false);
    }

    public void maybeCollectOnAllocation(UnsignedWord allocationSize) {
        boolean outOfMemory = false;
        if (hasNeverCollectPolicy()) {
            UnsignedWord edenUsed = HeapImpl.getAccounting().getEdenUsedBytes();
            outOfMemory = edenUsed.aboveThan(GCImpl.getPolicy().getMaximumHeapSize());
        } else if (getPolicy().shouldCollectOnAllocation()) {
            AllocationRequiringGCEvent.emit(getCollectionEpoch(), allocationSize);
            outOfMemory = collectWithoutAllocating(GenScavengeGCCause.OnAllocation, false);
            if (outOfMemory) {
                outOfMemory = getPolicy().isOutOfMemory(HeapImpl.getAccounting().getUsedBytes());
            }
        }
        if (outOfMemory) {
            throw OutOfMemoryUtil.heapSizeExceeded();
        }
    }

    @Override
    public void collectionHint(boolean fullGC) {
        if (policy.shouldCollectOnHint(fullGC)) {
            collect(GCCause.HintedGC, fullGC);
        }
    }

    private void collect(GCCause cause, boolean forceFullGC) {
        if (!hasNeverCollectPolicy()) {
            boolean outOfMemory = collectWithoutAllocating(cause, forceFullGC);
            if (outOfMemory) {
                if (getPolicy().isOutOfMemory(HeapImpl.getAccounting().getUsedBytes())) {
                    throw OutOfMemoryUtil.heapSizeExceeded();
                } else {
                    GCImpl.getPolicy().updateSizeParameters();
                }
            }
        }
    }

    @Uninterruptible(reason = "Avoid races with other threads that also try to trigger a GC")
    @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate in the implementation of garbage collection.")
    boolean collectWithoutAllocating(GCCause cause, boolean forceFullGC) {
        VMError.guarantee(!hasNeverCollectPolicy());

        int size = SizeOf.get(CollectionVMOperationData.class);
        CollectionVMOperationData data = StackValue.get(size);
        UnmanagedMemoryUtil.fill((Pointer) data, Word.unsigned(size), (byte) 0);
        data.setCauseId(cause.getId());
        data.setRequestingEpoch(getCollectionEpoch());
        data.setCompleteCollectionCount(GCImpl.getAccounting().getCompleteCollectionCount());
        data.setRequestingNanoTime(System.nanoTime());
        data.setForceFullGC(forceFullGC);
        enqueueCollectOperation(data);

        boolean outOfMemory = data.getOutOfMemory();
        if (outOfMemory && shouldIgnoreOutOfMemory()) {
            outOfMemory = false;
        }
        return outOfMemory;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static boolean shouldIgnoreOutOfMemory() {
        return SerialGCOptions.IgnoreMaxHeapSizeWhileInVMInternalCode.getValue() && (inVMInternalCode() || SuspendSerialGCMaxHeapSize.isSuspended());
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    private static boolean inVMInternalCode() {
        return VMOperation.isInProgress() || ReferenceHandlerThread.isReferenceHandlerThread();
    }

    @Uninterruptible(reason = "Used as a transition between uninterruptible and interruptible code", calleeMustBe = false)
    private void enqueueCollectOperation(CollectionVMOperationData data) {
        collectOperation.enqueue(data);
    }

    /** The body of the VMOperation to do the collection. */
    private void collectOperation(CollectionVMOperationData data) {
        assert VMOperation.isGCInProgress();
        assert getCollectionEpoch().equal(data.getRequestingEpoch()) ||
                        data.getForceFullGC() && GCImpl.getAccounting().getCompleteCollectionCount() == data.getCompleteCollectionCount() : "unnecessary GC?";

        timers.mutator.stopAt(data.getRequestingNanoTime());
        timers.resetAllExceptMutator();
        /* The type of collection will be determined later on. */
        completeCollection = false;

        JfrGCHeapSummaryEvent.emit(JfrGCWhen.BEFORE_GC);
        GCCause cause = GCCause.fromId(data.getCauseId());
        printGCBefore(cause);

        Timer collectionTimer = timers.collection.start();
        try {
            HeapImpl.getHeapImpl().makeParseable();

            GenScavengeMemoryPoolMXBeans.singleton().notifyBeforeCollection();
            HeapImpl.getAccounting().notifyBeforeCollection();

            verifyHeap(Before);

            boolean outOfMemory = collectImpl(cause, data.getRequestingNanoTime(), data.getForceFullGC());
            data.setOutOfMemory(outOfMemory);

            verifyHeap(After);
        } finally {
            collectionTimer.stop();
        }

        resizeAllTlabs();
        accounting.updateCollectionCountAndTime(completeCollection, collectionTimer.totalNanos());
        HeapImpl.getAccounting().notifyAfterCollection();
        GenScavengeMemoryPoolMXBeans.singleton().notifyAfterCollection();
        ChunkBasedCommittedMemoryProvider.get().afterGarbageCollection();

        printGCAfter(cause);
        JfrGCHeapSummaryEvent.emit(JfrGCWhen.AFTER_GC);

        collectionEpoch = collectionEpoch.add(1);
        timers.mutator.start();
    }

    private boolean collectImpl(GCCause cause, long requestingNanoTime, boolean forceFullGC) {
        boolean outOfMemory;
        long startTicks = JfrTicks.elapsedTicks();
        try {
            outOfMemory = doCollectImpl(cause, requestingNanoTime, forceFullGC, false);
            if (outOfMemory) {
                // Avoid running out of memory with a full GC that reclaims softly reachable
                // objects
                ReferenceObjectProcessing.setSoftReferencesAreWeak(true);
                try {
                    verifyHeap(During);
                    outOfMemory = doCollectImpl(cause, requestingNanoTime, true, true);
                } finally {
                    ReferenceObjectProcessing.setSoftReferencesAreWeak(false);
                }
            }
        } finally {
            JfrGCEvents.emitGarbageCollectionEvent(getCollectionEpoch(), cause, startTicks);
        }
        return outOfMemory;
    }

    private boolean doCollectImpl(GCCause cause, long requestingNanoTime, boolean forceFullGC, boolean forceNoIncremental) {
        checkSanityBeforeCollection();

        ChunkBasedCommittedMemoryProvider.get().beforeGarbageCollection();

        boolean incremental = !forceNoIncremental && !policy.shouldCollectCompletely(false);
        boolean outOfMemory = false;

        if (incremental) {
            long startTicks = JfrGCEvents.startGCPhasePause();
            try {
                outOfMemory = doCollectOnce(cause, requestingNanoTime, false, false);
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Incremental GC", startTicks);
            }
        }
        if (!incremental || outOfMemory || forceFullGC || policy.shouldCollectCompletely(incremental)) {
            if (incremental) { // uncommit unaligned chunks
                ChunkBasedCommittedMemoryProvider.get().uncommitUnusedMemory();
                verifyHeap(During);
            }
            long startTicks = JfrGCEvents.startGCPhasePause();
            try {
                outOfMemory = doCollectOnce(cause, requestingNanoTime, true, incremental);
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Full GC", startTicks);
            }
        }

        HeapImpl.getChunkProvider().freeExcessAlignedChunks();
        ChunkBasedCommittedMemoryProvider.get().uncommitUnusedMemory();

        checkSanityAfterCollection();
        return outOfMemory;
    }

    private boolean doCollectOnce(GCCause cause, long requestingNanoTime, boolean complete, boolean followsIncremental) {
        assert !followsIncremental || complete : "An incremental collection cannot be followed by another incremental collection";
        assert !completeCollection || complete : "After a complete collection, no further incremental collections may happen";
        completeCollection = complete;

        accounting.beforeCollectOnce(completeCollection);
        policy.onCollectionBegin(completeCollection, requestingNanoTime);

        doCollectCore(!complete);
        if (complete) {
            lastWholeHeapExaminedNanos = System.nanoTime();
        }

        accounting.afterCollectOnce(completeCollection);
        policy.onCollectionEnd(completeCollection, cause);

        UnsignedWord usedBytes = getChunkBytes();
        UnsignedWord freeBytes = policy.getCurrentHeapCapacity().subtract(usedBytes);
        ReferenceObjectProcessing.afterCollection(freeBytes);

        return usedBytes.aboveThan(policy.getMaximumHeapSize()); // out of memory?
    }

    private void verifyHeap(HeapVerifier.Occasion occasion) {
        if (SubstrateGCOptions.VerifyHeap.getValue() && shouldVerify(occasion)) {
            if (SubstrateGCOptions.VerboseGC.getValue()) {
                printGCPrefixAndTime().string("Verifying ").string(occasion.name()).string(" GC ").newline();
            }

            long start = System.nanoTime();

            boolean success = true;
            success &= HeapVerifier.singleton().verify(occasion);
            success &= StackVerifier.verifyAllThreads();

            if (!success) {
                String kind = getGCKind();
                Log.log().string("Heap verification ").string(occasion.name()).string(" GC failed (").string(kind).string(" garbage collection)").newline();
                throw VMError.shouldNotReachHere("Heap verification failed");
            }

            if (SubstrateGCOptions.VerboseGC.getValue()) {
                printGCPrefixAndTime().string("Verifying ").string(occasion.name()).string(" GC ")
                                .rational(TimeUtils.nanoSecondsSince(start), TimeUtils.nanosPerMilli, 3).string("ms").newline();
            }
        }
    }

    private static boolean shouldVerify(HeapVerifier.Occasion occasion) {
        return switch (occasion) {
            case Before -> SerialGCOptions.VerifyBeforeGC.getValue();
            case During -> SerialGCOptions.VerifyDuringGC.getValue();
            case After -> SerialGCOptions.VerifyAfterGC.getValue();
            default -> throw VMError.shouldNotReachHere("Unexpected heap verification occasion.");
        };
    }

    private String getGCKind() {
        return isCompleteCollection() ? "complete" : "incremental";
    }

    /**
     * This value is only updated during a GC, so it may be outdated if called from outside the GC
     * VM operation. Also be careful when calling this method during a GC as it might wrongly
     * include chunks that will be freed at the end of the GC.
     */
    public static UnsignedWord getChunkBytes() {
        UnsignedWord youngBytes = HeapImpl.getHeapImpl().getYoungGeneration().getChunkBytes();
        UnsignedWord oldBytes = HeapImpl.getHeapImpl().getOldGeneration().getChunkBytes();
        return youngBytes.add(oldBytes);
    }

    private static void resizeAllTlabs() {
        if (SubstrateGCOptions.TlabOptions.ResizeTLAB.getValue()) {
            for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
                TlabSupport.resize(thread);
            }
        }
    }

    private void printGCBefore(GCCause cause) {
        if (!SubstrateGCOptions.VerboseGC.getValue()) {
            return;
        }

        if (getCollectionEpoch().equal(0)) {
            printGCPrefixAndTime().string("Using ").string(getName()).newline();
            Log log = printGCPrefixAndTime().spaces(2).string("Memory: ");
            log.rational(PhysicalMemory.size(), M, 0).string("M").newline();
            printGCPrefixAndTime().spaces(2).string("GC policy: ").string(getPolicy().getName()).newline();
            printGCPrefixAndTime().spaces(2).string("Maximum young generation size: ").rational(getPolicy().getMaximumYoungGenerationSize(), M, 0).string("M").newline();
            printGCPrefixAndTime().spaces(2).string("Maximum heap size: ").rational(getPolicy().getMaximumHeapSize(), M, 0).string("M").newline();
            printGCPrefixAndTime().spaces(2).string("Minimum heap size: ").rational(getPolicy().getMinimumHeapSize(), M, 0).string("M").newline();
            printGCPrefixAndTime().spaces(2).string("Aligned chunk size: ").rational(HeapParameters.getAlignedHeapChunkSize(), K, 0).string("K").newline();
            printGCPrefixAndTime().spaces(2).string("Large array threshold: ").rational(HeapParameters.getLargeArrayThreshold(), K, 0).string("K").newline();
        }

        printGCPrefixAndTime().string(cause.getName()).newline();
    }

    private void printGCAfter(GCCause cause) {
        HeapAccounting heapAccounting = HeapImpl.getAccounting();
        HeapSizes beforeGc = heapAccounting.getHeapSizesBeforeGc();

        if (SubstrateGCOptions.VerboseGC.getValue()) {
            printHeapSizeChange("Eden", beforeGc.eden, heapAccounting.getEdenUsedBytes());
            printHeapSizeChange("Survivor", beforeGc.survivor, heapAccounting.getSurvivorUsedBytes());
            printHeapSizeChange("Old", beforeGc.old, heapAccounting.getOldUsedBytes());
            printHeapSizeChange("Free", beforeGc.free, heapAccounting.getBytesInUnusedChunks());

            if (SerialGCOptions.PrintGCTimes.getValue()) {
                timers.logAfterCollection(Log.log());
            }

            if (SerialGCOptions.TraceHeapChunks.getValue()) {
                HeapImpl.getHeapImpl().logChunks(Log.log(), false);
            }
        }

        if (SubstrateGCOptions.PrintGC.getValue() || SubstrateGCOptions.VerboseGC.getValue()) {
            String collectionType = completeCollection ? "Full GC" : "Incremental GC";
            printGCPrefixAndTime().string("Pause ").string(collectionType).string(" (").string(cause.getName()).string(") ")
                            .rational(beforeGc.totalUsed(), M, 2).string("M->").rational(heapAccounting.getUsedBytes(), M, 2).string("M ")
                            .rational(timers.collection.totalNanos(), TimeUtils.nanosPerMilli, 3).string("ms").newline();
        }
    }

    private void printHeapSizeChange(String text, UnsignedWord before, UnsignedWord after) {
        printGCPrefixAndTime().string("  ").string(text).string(": ").rational(before, M, 2).string("M->").rational(after, M, 2).string("M").newline();
    }

    private Log printGCPrefixAndTime() {
        long uptimeMs = Isolates.getUptimeMillis();
        return Log.log().string("[").rational(uptimeMs, TimeUtils.millisPerSecond, 3).string("s").string("] GC(").unsigned(collectionEpoch).string(") ");
    }

    private static void checkSanityBeforeCollection() {
        HeapImpl heap = HeapImpl.getHeapImpl();
        heap.getYoungGeneration().checkSanityBeforeCollection();
        heap.getOldGeneration().checkSanityBeforeCollection();
    }

    private static void checkSanityAfterCollection() {
        HeapImpl heap = HeapImpl.getHeapImpl();
        heap.getYoungGeneration().checkSanityAfterCollection();
        heap.getOldGeneration().checkSanityAfterCollection();
    }

    @Fold
    static boolean runtimeAssertions() {
        return RuntimeAssertionsSupport.singleton().desiredAssertionStatus(GCImpl.class);
    }

    @Fold
    public static GCImpl getGCImpl() {
        GCImpl gcImpl = HeapImpl.getGCImpl();
        assert gcImpl != null;
        return gcImpl;
    }

    @Override
    public void collectCompletely(GCCause cause) {
        collect(cause, true);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public boolean isCompleteCollection() {
        return completeCollection;
    }

    /** Collect, either incrementally or completely, and process discovered references. */
    private void doCollectCore(boolean incremental) {
        GreyToBlackObjRefVisitor.Counters counters = greyToBlackObjRefVisitor.openCounters();
        long startTicks;
        try {
            Timer rootScanTimer = timers.rootScan.start();
            try {
                startTicks = JfrGCEvents.startGCPhasePause();
                try {
                    /* Scan reachable objects and potentially already copy them once discovered. */
                    scan(incremental);
                } finally {
                    JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), incremental ? "Incremental Scan" : "Scan", startTicks);
                }
            } finally {
                rootScanTimer.stop();
            }

            if (!incremental) {
                /* Sweep or compact objects in the old generation unless already done by copying. */
                HeapImpl.getHeapImpl().getOldGeneration().sweepAndCompact(timers, chunkReleaser);
            }

            Timer referenceObjectsTimer = timers.referenceObjects.start();
            try {
                startTicks = JfrGCEvents.startGCPhasePause();
                try {
                    Reference<?> newlyPendingList = ReferenceObjectProcessing.processRememberedReferences();
                    HeapImpl.getHeapImpl().addToReferencePendingList(newlyPendingList);
                } finally {
                    JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Process Remembered References", startTicks);
                }
            } finally {
                referenceObjectsTimer.stop();
            }

            if (RuntimeCompilation.isEnabled()) {
                Timer cleanCodeCacheTimer = timers.cleanCodeCache.start();
                try {
                    /*
                     * Cleaning the code cache may invalidate code, which is a rather complex
                     * operation. To avoid side-effects between the code cache cleaning and the GC
                     * core, it is crucial that all the GC core work finished before.
                     */
                    startTicks = JfrGCEvents.startGCPhasePause();
                    try {
                        cleanRuntimeCodeCache();
                    } finally {
                        JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Clean Runtime CodeCache", startTicks);
                    }
                } finally {
                    cleanCodeCacheTimer.stop();
                }
            }

            Timer releaseSpacesTimer = timers.releaseSpaces.start();
            try {
                assert SerialGCOptions.useCompactingOldGen() || chunkReleaser.isEmpty();
                startTicks = JfrGCEvents.startGCPhasePause();
                try {
                    releaseSpaces();

                    /*
                     * With a copying collector, do not uncommit any aligned chunks yet if we just
                     * did an incremental GC so if we decide to do a full GC next, we can reuse the
                     * chunks for copying live old objects with fewer chunk allocations. In either
                     * case, excess chunks are released later.
                     */
                    boolean keepAllAlignedChunks = !SerialGCOptions.useCompactingOldGen() && incremental;
                    chunkReleaser.release(keepAllAlignedChunks);
                } finally {
                    JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Release Spaces", startTicks);
                }
            } finally {
                releaseSpacesTimer.stop();
            }

            startTicks = JfrGCEvents.startGCPhasePause();
            try {
                swapSpaces();
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Swap Spaces", startTicks);
            }
        } finally {
            counters.close();
        }
    }

    /**
     * Visit all the memory that is reserved for runtime compiled code. References from the runtime
     * compiled code to the Java heap must be consider as either strong or weak references,
     * depending on whether the code is currently on the execution stack.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void walkRuntimeCodeCache() {
        Timer walkRuntimeCodeCacheTimer = timers.walkRuntimeCodeCache.start();
        try {
            RuntimeCodeInfoMemory.singleton().walkRuntimeMethodsDuringGC(runtimeCodeCacheWalker);
        } finally {
            walkRuntimeCodeCacheTimer.stop();
        }
    }

    private void cleanRuntimeCodeCache() {
        Timer cleanRuntimeCodeCacheTimer = timers.cleanRuntimeCodeCache.start();
        try {
            RuntimeCodeInfoMemory.singleton().walkRuntimeMethodsDuringGC(runtimeCodeCacheCleaner);
        } finally {
            cleanRuntimeCodeCacheTimer.stop();
        }
    }

    @Uninterruptible(reason = "We don't want any safepoint checks in the core part of the GC.")
    private void scan(boolean incremental) {
        if (incremental) {
            scanFromDirtyRoots();
        } else {
            scanFromRoots();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void scanFromRoots() {
        Timer scanFromRootsTimer = timers.scanFromRoots.start();
        try {
            long startTicks = JfrGCEvents.startGCPhasePause();
            try {
                /*
                 * Snapshot the heap so that objects that are promoted afterwards can be visited.
                 * When using a compacting old generation, it absorbs all chunks from the young
                 * generation at this point.
                 */
                beginPromotion(false);
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Snapshot Heap", startTicks);
            }

            startTicks = JfrGCEvents.startGCPhasePause();
            try {
                /*
                 * Make sure all chunks with pinned objects are in toSpace, and any formerly pinned
                 * objects are in fromSpace.
                 */
                promoteChunksWithPinnedObjects();
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Promote Pinned Objects", startTicks);
            }

            startTicks = JfrGCEvents.startGCPhasePause();
            try {
                /*
                 * Stack references are grey at the beginning of a collection, so I need to blacken
                 * them.
                 */
                blackenStackRoots();

                /* Custom memory regions which contain object references. */
                walkThreadLocals();

                /*
                 * Native image Objects are grey at the beginning of a collection, so I need to
                 * blacken them.
                 */
                blackenImageHeapRoots();
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Scan Roots", startTicks);
            }

            startTicks = JfrGCEvents.startGCPhasePause();
            try {
                /* Visit all the Objects promoted since the snapshot. */
                scanGreyObjects(false);

                if (RuntimeCompilation.isEnabled()) {
                    /*
                     * Visit the runtime compiled code, now that we know all the reachable objects.
                     */
                    walkRuntimeCodeCache();

                    /* Visit all objects that became reachable because of the compiled code. */
                    scanGreyObjects(false);
                }
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Scan From Roots", startTicks);
            }
        } finally {
            scanFromRootsTimer.stop();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void scanFromDirtyRoots() {
        Timer scanFromDirtyRootsTimer = timers.scanFromDirtyRoots.start();
        try {
            long startTicks = JfrGCEvents.startGCPhasePause();

            try {
                /* Snapshot the heap so that objects that are promoted afterwards can be visited. */
                beginPromotion(true);
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Snapshot Heap", startTicks);
            }

            startTicks = JfrGCEvents.startGCPhasePause();
            try {
                /*
                 * Make sure any released objects are in toSpace (because this is an incremental
                 * collection). I do this before blackening any roots to make sure the chunks with
                 * pinned objects are moved entirely, as opposed to promoting the objects
                 * individually by roots. This makes the objects in those chunks grey.
                 */
                promoteChunksWithPinnedObjects();
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Promote Pinned Objects", startTicks);
            }

            startTicks = JfrGCEvents.startGCPhasePause();
            try {
                /*
                 * Blacken Objects that are dirty roots. There are dirty cards in ToSpace. Do this
                 * early so I don't have to walk the cards of individually promoted objects, which
                 * will be visited by the grey object scanner.
                 */
                blackenDirtyCardRoots();

                /*
                 * Stack references are grey at the beginning of a collection, so I need to blacken
                 * them.
                 */
                blackenStackRoots();

                /* Custom memory regions which contain object references. */
                walkThreadLocals();

                /*
                 * Native image Objects are grey at the beginning of a collection, so I need to
                 * blacken them.
                 */
                blackenDirtyImageHeapRoots();
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Scan Roots", startTicks);
            }

            startTicks = JfrGCEvents.startGCPhasePause();
            try {
                /* Visit all the Objects promoted since the snapshot, transitively. */
                scanGreyObjects(true);

                if (RuntimeCompilation.isEnabled()) {
                    /*
                     * Visit the runtime compiled code, now that we know all the reachable objects.
                     */
                    walkRuntimeCodeCache();

                    /* Visit all objects that became reachable because of the compiled code. */
                    scanGreyObjects(true);
                }
            } finally {
                JfrGCEvents.emitGCPhasePauseEvent(getCollectionEpoch(), "Scan From Roots", startTicks);
            }
        } finally {
            scanFromDirtyRootsTimer.stop();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void promoteChunksWithPinnedObjects() {
        Timer promotePinnedObjectsTimer = timers.promotePinnedObjects.start();
        try {
            // Remove closed pinned objects from the global list. This code needs to use write
            // barriers as the PinnedObjectImpls are a linked list, and we don't know in which
            // generation each individual PinnedObjectImpl lives. So, the card table will be
            // modified.
            PinnedObjectImpl cur = AbstractPinnedObjectSupport.singleton().removeClosedObjectsAndGetFirstOpenObject();

            // Promote all chunks that contain pinned objects. The card table of the promoted chunks
            // will be cleaned.
            while (cur != null) {
                promotePinnedObject(cur.getObject());
                cur = cur.getNext();
            }
        } finally {
            promotePinnedObjectsTimer.stop();
        }
    }

    @NeverInline("Starting a stack walk in the caller frame. " +
                    "Note that we could start the stack frame also further down the stack, because GC stack frames must not access any objects that are processed by the GC. " +
                    "But we don't store stack frame information for the first frame we would need to process.")
    @Uninterruptible(reason = "Required by called JavaStackWalker methods. We are at a safepoint during GC, so it does not change anything for this method.")
    private void blackenStackRoots() {
        Timer blackenStackRootsTimer = timers.blackenStackRoots.start();
        try {
            Pointer sp = KnownIntrinsics.readCallerStackPointer();
            walkStackRoots(greyToBlackObjRefVisitor, sp, true);
        } finally {
            blackenStackRootsTimer.stop();
        }
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Required by called JavaStackWalker methods. We are at a safepoint during GC, so it does not change anything for this method.", mayBeInlined = true)
    static void walkStackRoots(ObjectReferenceVisitor visitor, Pointer currentThreadSp, boolean visitRuntimeCodeInfo) {
        /*
         * Walk the current thread (unlike all other threads, it does not have a usable frame
         * anchor).
         */
        JavaStackWalk walk = StackValue.get(JavaStackWalker.sizeOfJavaStackWalk());
        JavaStackWalker.initialize(walk, CurrentIsolate.getCurrentThread(), currentThreadSp);
        walkStack(CurrentIsolate.getCurrentThread(), walk, visitor, visitRuntimeCodeInfo);

        /*
         * Scan the stacks of all the threads. Other threads will be blocked at a safepoint (or in
         * native code) so they will each have a JavaFrameAnchor in their VMThread.
         */
        for (IsolateThread thread = VMThreads.firstThread(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
            if (thread == CurrentIsolate.getCurrentThread()) {
                continue;
            }
            JavaStackWalker.initialize(walk, thread);
            walkStack(thread, walk, visitor, visitRuntimeCodeInfo);
        }
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Required by called JavaStackWalker methods. We are at a safepoint during GC, so it does not change anything for this method.", mayBeInlined = true)
    private static void walkStack(IsolateThread thread, JavaStackWalk walk, ObjectReferenceVisitor visitor, boolean visitRuntimeCodeInfo) {
        assert VMOperation.isGCInProgress() : "This methods accesses a CodeInfo without a tether";

        while (JavaStackWalker.advance(walk, thread)) {
            JavaFrame frame = JavaStackWalker.getCurrentFrame(walk);
            VMError.guarantee(!JavaFrames.isUnknownFrame(frame), "GC must not encounter unknown frames");

            /* We are during a GC, so tethering of the CodeInfo is not necessary. */
            DeoptimizedFrame deoptFrame = Deoptimizer.checkEagerDeoptimized(frame);
            if (deoptFrame == null) {
                Pointer sp = frame.getSP();
                CodeInfo codeInfo = CodeInfoAccess.unsafeConvert(frame.getIPCodeInfo());

                if (JavaFrames.isInterpreterLeaveStub(frame)) {
                    /*
                     * Variable frame size is packed into the first stack slot used for argument
                     * passing (re-use of deopt slot).
                     */
                    long varStackSize = DeoptimizationSlotPacking.decodeVariableFrameSizeFromDeoptSlot(sp.readLong(0));
                    Pointer actualSP = sp.add(Word.unsigned(varStackSize));

                    InterpreterSupport.walkInterpreterLeaveStubFrame(visitor, actualSP, sp);
                } else {
                    NonmovableArray<Byte> referenceMapEncoding = CodeInfoAccess.getStackReferenceMapEncoding(codeInfo);
                    long referenceMapIndex = frame.getReferenceMapIndex();
                    if (referenceMapIndex == ReferenceMapIndex.NO_REFERENCE_MAP) {
                        throw CodeInfoTable.fatalErrorNoReferenceMap(sp, frame.getIP(), codeInfo);
                    }

                    CodeReferenceMapDecoder.walkOffsetsFromPointer(sp, referenceMapEncoding, referenceMapIndex, visitor, null);

                    if (RuntimeCompilation.isEnabled() && visitRuntimeCodeInfo && !CodeInfoAccess.isAOTImageCode(codeInfo)) {
                        /*
                         * Runtime-compiled code that is currently on the stack must be kept alive.
                         * So, we mark the tether as strongly reachable. The RuntimeCodeCacheWalker
                         * will handle all other object references later on.
                         */
                        RuntimeCodeInfoAccess.walkTether(codeInfo, visitor);
                    }
                }
            } else {
                /*
                 * This is a deoptimized frame. The DeoptimizedFrame object is stored in the frame,
                 * but it is pinned so we do not need to visit references of the frame.
                 */
            }
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void walkThreadLocals() {
        Timer walkThreadLocalsTimer = timers.walkThreadLocals.start();
        try {
            for (IsolateThread isolateThread = VMThreads.firstThread(); isolateThread.isNonNull(); isolateThread = VMThreads.nextThread(isolateThread)) {
                VMThreadLocalSupport.singleton().walk(isolateThread, greyToBlackObjRefVisitor);
            }
        } finally {
            walkThreadLocalsTimer.stop();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void blackenDirtyImageHeapRoots() {
        if (!HeapImpl.usesImageHeapCardMarking()) {
            blackenImageHeapRoots();
            return;
        }

        Timer blackenImageHeapRootsTimer = timers.blackenImageHeapRoots.start();
        try {
            for (ImageHeapInfo info : HeapImpl.getImageHeapInfos()) {
                blackenDirtyImageHeapChunkRoots(info);
            }

            if (AuxiliaryImageHeap.isPresent()) {
                ImageHeapInfo auxInfo = AuxiliaryImageHeap.singleton().getImageHeapInfo();
                if (auxInfo != null) {
                    blackenDirtyImageHeapChunkRoots(auxInfo);
                }
            }
        } finally {
            blackenImageHeapRootsTimer.stop();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void blackenDirtyImageHeapChunkRoots(ImageHeapInfo info) {
        /*
         * We clean and remark cards of the image heap only during complete collections when we also
         * collect the old generation and can easily remark references into it. It also only makes a
         * difference after references to the runtime heap were nulled, which is assumed to be rare.
         */
        boolean clean = completeCollection;
        walkDirtyImageHeapChunkRoots(info, greyToBlackObjectVisitor, greyToBlackObjRefVisitor, clean);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void walkDirtyImageHeapChunkRoots(ImageHeapInfo info, UninterruptibleObjectVisitor visitor, UninterruptibleObjectReferenceVisitor refVisitor, boolean clean) {
        RememberedSet.get().walkDirtyObjects(info.getFirstWritableAlignedChunk(), info.getFirstWritableUnalignedChunk(), info.getLastWritableUnalignedChunk(), visitor, refVisitor, clean);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void blackenImageHeapRoots() {
        if (HeapImpl.usesImageHeapCardMarking()) {
            // Avoid scanning the entire image heap even for complete collections: its remembered
            // set contains references into both the runtime heap's old and young generations.
            blackenDirtyImageHeapRoots();
            return;
        }

        Timer blackenImageHeapRootsTimer = timers.blackenImageHeapRoots.start();
        try {
            for (ImageHeapInfo info : HeapImpl.getImageHeapInfos()) {
                blackenImageHeapRoots(info);
            }

            if (AuxiliaryImageHeap.isPresent()) {
                ImageHeapInfo auxImageHeapInfo = AuxiliaryImageHeap.singleton().getImageHeapInfo();
                if (auxImageHeapInfo != null) {
                    blackenImageHeapRoots(auxImageHeapInfo);
                }
            }
        } finally {
            blackenImageHeapRootsTimer.stop();
        }
    }

    @Uninterruptible(reason = "Forced inlining (StoredContinuation objects must not move).")
    private void blackenImageHeapRoots(ImageHeapInfo imageHeapInfo) {
        walkImageHeapRoots(imageHeapInfo, greyToBlackObjectVisitor);
    }

    @AlwaysInline("GC Performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void walkImageHeapRoots(ImageHeapInfo imageHeapInfo, ObjectVisitor visitor) {
        ImageHeapWalker.walkPartitionInline(imageHeapInfo.firstWritableRegularObject, imageHeapInfo.lastWritableRegularObject, visitor, true);
        ImageHeapWalker.walkPartitionInline(imageHeapInfo.firstWritableHugeObject, imageHeapInfo.lastWritableHugeObject, visitor, false);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void blackenDirtyCardRoots() {
        Timer blackenDirtyCardRootsTimer = timers.blackenDirtyCardRoots.start();
        try {
            /*
             * Walk old generation looking for dirty cards, and within those for old-to-young
             * pointers. Promote any referenced young objects.
             */
            HeapImpl.getHeapImpl().getOldGeneration().blackenDirtyCardRoots(greyToBlackObjectVisitor, greyToBlackObjRefVisitor);
        } finally {
            blackenDirtyCardRootsTimer.stop();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void beginPromotion(boolean isIncremental) {
        HeapImpl heap = HeapImpl.getHeapImpl();
        heap.getOldGeneration().beginPromotion(isIncremental);
        if (isIncremental) {
            heap.getYoungGeneration().beginPromotion();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void scanGreyObjects(boolean isIncremental) {
        Timer scanGreyObjectsTimer = timers.scanGreyObjects.start();
        try {
            if (isIncremental) {
                incrementalScanGreyObjectsLoop();
            } else {
                HeapImpl.getHeapImpl().getOldGeneration().scanGreyObjects(false);
            }
        } finally {
            scanGreyObjectsTimer.stop();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void incrementalScanGreyObjectsLoop() {
        HeapImpl heap = HeapImpl.getHeapImpl();
        YoungGeneration youngGen = heap.getYoungGeneration();
        OldGeneration oldGen = heap.getOldGeneration();
        boolean hasGrey;
        do {
            hasGrey = youngGen.scanGreyObjects();
            hasGrey |= oldGen.scanGreyObjects(true);
        } while (hasGrey);
    }

    @AlwaysInline("GC performance")
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @SuppressWarnings("static-method")
    Object promoteObject(Object original, UnsignedWord header) {
        HeapImpl heap = HeapImpl.getHeapImpl();
        boolean isAligned = ObjectHeaderImpl.isAlignedHeader(header);
        Header<?> originalChunk = getChunk(original, isAligned);
        Space originalSpace = HeapChunk.getSpace(originalChunk);
        if (originalSpace.isToSpace()) {
            assert !SerialGCOptions.useCompactingOldGen() || !completeCollection;
            return original;
        }

        Object result = null;
        if (!completeCollection && originalSpace.getNextAgeForPromotion() < policy.getTenuringAge()) {
            if (isAligned) {
                result = heap.getYoungGeneration().promoteAlignedObject(original, (AlignedHeader) originalChunk, originalSpace);
            } else {
                result = heap.getYoungGeneration().promoteUnalignedObject(original, (UnalignedHeader) originalChunk, originalSpace);
            }
            if (result == null) {
                accounting.onSurvivorOverflowed();
            }
        }
        if (result == null) { // complete collection, tenuring age reached, or survivor space full
            if (isAligned) {
                result = heap.getOldGeneration().promoteAlignedObject(original, (AlignedHeader) originalChunk, originalSpace);
            } else {
                result = heap.getOldGeneration().promoteUnalignedObject(original, (UnalignedHeader) originalChunk, originalSpace);
            }
            assert result != null : "promotion failure in old generation must have been handled";
        }

        return result;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Header<?> getChunk(Object obj, boolean isAligned) {
        if (isAligned) {
            return AlignedHeapChunk.getEnclosingChunk(obj);
        }
        assert ObjectHeaderImpl.isUnalignedObject(obj);
        return UnalignedHeapChunk.getEnclosingChunk(obj);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private void promotePinnedObject(Object pinned) {
        assert pinned != null;
        assert !Heap.getHeap().isInImageHeap(pinned);
        assert HeapChunk.getEnclosingHeapChunk(pinned).getPinnedObjectCount() > 0;

        HeapImpl heap = HeapImpl.getHeapImpl();
        boolean isAligned = ObjectHeaderImpl.isAlignedObject(pinned);
        Header<?> originalChunk = getChunk(pinned, isAligned);
        Space originalSpace = HeapChunk.getSpace(originalChunk);
        if (originalSpace.isFromSpace() || (originalSpace.isCompactingOldSpace() && completeCollection)) {
            boolean promoted = false;
            if (!completeCollection && originalSpace.getNextAgeForPromotion() < policy.getTenuringAge()) {
                promoted = heap.getYoungGeneration().promotePinnedObject(pinned, originalChunk, isAligned, originalSpace);
                if (!promoted) {
                    accounting.onSurvivorOverflowed();
                }
            }
            if (!promoted) {
                heap.getOldGeneration().promotePinnedObject(pinned, originalChunk, isAligned, originalSpace);
            }
        }
    }

    private static void swapSpaces() {
        HeapImpl heap = HeapImpl.getHeapImpl();
        heap.getYoungGeneration().swapSpaces();
        heap.getOldGeneration().swapSpaces();
    }

    private void releaseSpaces() {
        HeapImpl heap = HeapImpl.getHeapImpl();

        heap.getYoungGeneration().releaseSpaces(chunkReleaser);
        if (completeCollection) {
            heap.getOldGeneration().releaseSpaces(chunkReleaser);
        }
    }

    /**
     * Inside a VMOperation, we are not allowed to do certain things, e.g., perform synchronization
     * (because it can deadlock when a lock is held outside the VMOperation). Similar restrictions
     * apply if we are too early in the attach sequence of a thread.
     */
    static void doReferenceHandling() {
        assert !VMOperation.isInProgress() : "could result in deadlocks";
        assert PlatformThreads.isCurrentAssigned() : "thread is not fully initialized yet";
        /* Most of the time, we won't have a pending reference list. So, we do that check first. */
        if (HeapImpl.getHeapImpl().hasReferencePendingListUnsafe()) {
            long startTime = System.nanoTime();
            ReferenceHandler.processPendingReferencesInRegularThread();

            if (SubstrateGCOptions.VerboseGC.getValue() && SerialGCOptions.PrintGCTimes.getValue()) {
                long executionTime = System.nanoTime() - startTime;
                Log.log().string("[GC epilogue reference processing and cleaners: ").signed(executionTime).string("]").newline();
            }
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public UnsignedWord getCollectionEpoch() {
        return collectionEpoch;
    }

    public long getMillisSinceLastWholeHeapExamined() {
        long start;
        if (lastWholeHeapExaminedNanos < 0) {
            // no full GC has yet been run, use time since the first allocation
            start = Isolates.getStartTimeNanos();
        } else {
            start = lastWholeHeapExaminedNanos;
        }
        return TimeUtils.millisSinceNanos(start);
    }

    @Fold
    public static GCAccounting getAccounting() {
        return GCImpl.getGCImpl().accounting;
    }

    @Fold
    public static CollectionPolicy getPolicy() {
        return GCImpl.getGCImpl().policy;
    }

    @Fold
    public static boolean hasNeverCollectPolicy() {
        return getPolicy() instanceof NeverCollect;
    }

    @Fold
    GreyToBlackObjectVisitor getGreyToBlackObjectVisitor() {
        return greyToBlackObjectVisitor;
    }

    private static class CollectionVMOperation extends NativeVMOperation {
        private final NoAllocationVerifier noAllocationVerifier = NoAllocationVerifier.factory("CollectionVMOperation", false);

        CollectionVMOperation() {
            super(VMOperationInfos.get(CollectionVMOperation.class, "Garbage collection", SystemEffect.SAFEPOINT));
        }

        @Override
        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public boolean isGC() {
            return true;
        }

        @Override
        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate while collecting")
        protected void operate(NativeVMOperationData data) {
            NoAllocationVerifier nav = noAllocationVerifier.open();
            try {
                collect((CollectionVMOperationData) data);
            } catch (Throwable t) {
                throw VMError.shouldNotReachHere(t);
            } finally {
                nav.close();
            }
        }

        private static void collect(CollectionVMOperationData data) {
            /*
             * Exceptions during collections are fatal. The heap is likely in an inconsistent state.
             * The GC must also be allocation free, i.e., we cannot allocate exception stack traces
             * while in the GC. This is bad for diagnosing errors in the GC. To improve the
             * situation a bit, we switch on the flag to make implicit exceptions such as
             * NullPointerExceptions fatal errors. This ensures that we fail early at the place
             * where the fatal error reporting can still dump the full stack trace.
             */
            ImplicitExceptions.activateImplicitExceptionsAreFatal();
            try {
                HeapImpl.getGCImpl().collectOperation(data);
            } finally {
                ImplicitExceptions.deactivateImplicitExceptionsAreFatal();
            }
        }

        @Override
        protected boolean hasWork(NativeVMOperationData data) {
            CollectionVMOperationData d = (CollectionVMOperationData) data;
            if (d.getForceFullGC()) {
                /* Skip if another full GC happened in the meanwhile. */
                return GCImpl.getAccounting().getCompleteCollectionCount() == d.getCompleteCollectionCount();
            }
            /* Skip if any other GC happened in the meanwhile. */
            return GCImpl.getGCImpl().getCollectionEpoch().equal(d.getRequestingEpoch());
        }
    }

    @RawStructure
    private interface CollectionVMOperationData extends NativeVMOperationData {
        @RawField
        int getCauseId();

        @RawField
        void setCauseId(int value);

        @RawField
        UnsignedWord getRequestingEpoch();

        @RawField
        void setRequestingEpoch(UnsignedWord value);

        @RawField
        long getRequestingNanoTime();

        @RawField
        void setRequestingNanoTime(long value);

        @RawField
        boolean getForceFullGC();

        @RawField
        void setForceFullGC(boolean value);

        @RawField
        long getCompleteCollectionCount();

        @RawField
        void setCompleteCollectionCount(long value);

        @RawField
        boolean getOutOfMemory();

        @RawField
        void setOutOfMemory(boolean value);
    }

    public static class ChunkReleaser {
        private AlignedHeader firstAligned;
        private UnalignedHeader firstUnaligned;

        @Platforms(Platform.HOSTED_ONLY.class)
        ChunkReleaser() {
        }

        public boolean isEmpty() {
            return firstAligned.isNull() && firstUnaligned.isNull();
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public void add(AlignedHeader chunks) {
            if (chunks.isNonNull()) {
                assert HeapChunk.getPrevious(chunks).isNull() : "prev must be null";
                if (firstAligned.isNonNull()) {
                    AlignedHeader lastNewChunk = getLast(chunks);
                    HeapChunk.setNext(lastNewChunk, firstAligned);
                    HeapChunk.setPrevious(firstAligned, lastNewChunk);
                }
                firstAligned = chunks;
            }
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        public void add(UnalignedHeader chunks) {
            if (chunks.isNonNull()) {
                assert HeapChunk.getPrevious(chunks).isNull() : "prev must be null";
                if (firstUnaligned.isNonNull()) {
                    UnalignedHeader lastNewChunk = getLast(chunks);
                    HeapChunk.setNext(lastNewChunk, firstUnaligned);
                    HeapChunk.setPrevious(firstUnaligned, lastNewChunk);
                }
                firstUnaligned = chunks;
            }
        }

        void release(boolean keepAllAlignedChunks) {
            if (firstAligned.isNonNull()) {
                HeapImpl.getChunkProvider().consumeAlignedChunks(firstAligned, keepAllAlignedChunks);
                firstAligned = Word.nullPointer();
            }
            if (firstUnaligned.isNonNull()) {
                HeapChunkProvider.consumeUnalignedChunks(firstUnaligned);
                firstUnaligned = Word.nullPointer();
            }
        }

        @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
        private static <T extends Header<T>> T getLast(T chunks) {
            T prev = chunks;
            T next = HeapChunk.getNext(prev);
            while (next.isNonNull()) {
                prev = next;
                next = HeapChunk.getNext(prev);
            }
            return prev;
        }
    }

    private static void printGCSummary() {
        if (!SerialGCOptions.PrintGCSummary.getValue()) {
            return;
        }

        PrintGCSummaryOperation vmOp = new PrintGCSummaryOperation();
        vmOp.enqueue();
    }

    private static class PrintGCSummaryOperation extends JavaVMOperation {
        protected PrintGCSummaryOperation() {
            super(VMOperationInfos.get(PrintGCSummaryOperation.class, "Print GC summary", SystemEffect.SAFEPOINT));
        }

        @Override
        protected void operate() {
            HeapImpl.getHeapImpl().makeParseable();

            Log log = Log.log();
            log.string("GC summary").indent(true);
            HeapImpl heap = HeapImpl.getHeapImpl();
            Space edenSpace = heap.getYoungGeneration().getEden();
            UnsignedWord youngChunkBytes = edenSpace.getChunkBytes();
            UnsignedWord youngObjectBytes = edenSpace.computeObjectBytes();

            GCAccounting accounting = GCImpl.getAccounting();
            UnsignedWord allocatedChunkBytes = accounting.getTotalAllocatedChunkBytes().add(youngChunkBytes);
            UnsignedWord allocatedObjectBytes = accounting.getAllocatedObjectBytes().add(youngObjectBytes);

            log.string("Collected chunk bytes: ").rational(accounting.getTotalCollectedChunkBytes(), M, 2).string("M").newline();
            log.string("Collected object bytes: ").rational(accounting.getTotalCollectedObjectBytes(), M, 2).string("M").newline();
            log.string("Allocated chunk bytes: ").rational(allocatedChunkBytes, M, 2).string("M").newline();
            log.string("Allocated object bytes: ").rational(allocatedObjectBytes, M, 2).string("M").newline();

            long incrementalNanos = accounting.getIncrementalCollectionTotalNanos();
            log.string("Incremental GC count: ").signed(accounting.getIncrementalCollectionCount()).newline();
            log.string("Incremental GC time: ").rational(incrementalNanos, TimeUtils.nanosPerSecond, 3).string("s").newline();
            long completeNanos = accounting.getCompleteCollectionTotalNanos();
            log.string("Complete GC count: ").signed(accounting.getCompleteCollectionCount()).newline();
            log.string("Complete GC time: ").rational(completeNanos, TimeUtils.nanosPerSecond, 3).string("s").newline();

            long gcNanos = incrementalNanos + completeNanos;

            long mutatorNanos = GCImpl.getGCImpl().timers.mutator.totalNanos();
            long totalNanos = gcNanos + mutatorNanos;
            long roundedGCLoad = (0 < totalNanos ? TimeUtils.roundedDivide(100 * gcNanos, totalNanos) : 0);
            log.string("GC time: ").rational(gcNanos, TimeUtils.nanosPerSecond, 3).string("s").newline();
            log.string("Run time: ").rational(totalNanos, TimeUtils.nanosPerSecond, 3).string("s").newline();
            log.string("GC load: ").signed(roundedGCLoad).string("%").indent(false);
        }
    }
}
