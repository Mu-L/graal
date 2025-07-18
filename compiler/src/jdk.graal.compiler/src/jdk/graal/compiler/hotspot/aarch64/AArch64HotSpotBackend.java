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
package jdk.graal.compiler.hotspot.aarch64;

import static java.lang.reflect.Modifier.isStatic;
import static jdk.graal.compiler.asm.aarch64.AArch64Address.AddressingMode.IMMEDIATE_UNSIGNED_SCALED;
import static jdk.graal.compiler.asm.aarch64.AArch64Assembler.ShiftType.LSL;
import static jdk.graal.compiler.asm.aarch64.AArch64Assembler.ShiftType.LSR;
import static jdk.graal.compiler.core.common.GraalOptions.ZapStackOnMethodEntry;
import static jdk.vm.ci.aarch64.AArch64.lr;
import static jdk.vm.ci.aarch64.AArch64.r10;
import static jdk.vm.ci.aarch64.AArch64.rscratch1;
import static jdk.vm.ci.aarch64.AArch64.sp;
import static jdk.vm.ci.aarch64.AArch64.zr;
import static jdk.vm.ci.code.ValueUtil.asRegister;
import static jdk.vm.ci.hotspot.aarch64.AArch64HotSpotRegisterConfig.fp;
import static jdk.vm.ci.hotspot.aarch64.AArch64HotSpotRegisterConfig.heapBaseRegister;

import jdk.graal.compiler.asm.BranchTargetOutOfBoundsException;
import jdk.graal.compiler.asm.Label;
import jdk.graal.compiler.asm.aarch64.AArch64Address;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler.ScratchRegister;
import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.code.DataSection;
import jdk.graal.compiler.core.aarch64.AArch64NodeMatchRules;
import jdk.graal.compiler.core.common.alloc.RegisterAllocationConfig;
import jdk.graal.compiler.core.common.spi.ForeignCallLinkage;
import jdk.graal.compiler.core.gen.LIRGenerationProvider;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.hotspot.HotSpotDataBuilder;
import jdk.graal.compiler.hotspot.HotSpotGraalRuntimeProvider;
import jdk.graal.compiler.hotspot.HotSpotHostBackend;
import jdk.graal.compiler.hotspot.HotSpotLIRGenerationResult;
import jdk.graal.compiler.hotspot.HotSpotMarkId;
import jdk.graal.compiler.hotspot.meta.HotSpotForeignCallsProvider;
import jdk.graal.compiler.hotspot.meta.HotSpotHostForeignCallsProvider;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.hotspot.stubs.Stub;
import jdk.graal.compiler.lir.LIR;
import jdk.graal.compiler.lir.aarch64.AArch64Call;
import jdk.graal.compiler.lir.aarch64.AArch64FrameMap;
import jdk.graal.compiler.lir.aarch64.AArch64FrameMapBuilder;
import jdk.graal.compiler.lir.asm.CompilationResultBuilder;
import jdk.graal.compiler.lir.asm.CompilationResultBuilderFactory;
import jdk.graal.compiler.lir.asm.DataBuilder;
import jdk.graal.compiler.lir.asm.EntryPointDecorator;
import jdk.graal.compiler.lir.asm.FrameContext;
import jdk.graal.compiler.lir.framemap.FrameMap;
import jdk.graal.compiler.lir.framemap.FrameMapBuilder;
import jdk.graal.compiler.lir.gen.LIRGenerationResult;
import jdk.graal.compiler.lir.gen.LIRGeneratorTool;
import jdk.graal.compiler.lir.gen.MoveFactory;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.vector.lir.VectorLIRGeneratorTool;
import jdk.graal.compiler.vector.lir.aarch64.AArch64VectorArithmeticLIRGenerator;
import jdk.graal.compiler.vector.lir.aarch64.AArch64VectorMoveFactory;
import jdk.graal.compiler.vector.lir.aarch64.AArch64VectorNodeMatchRules;
import jdk.graal.compiler.vector.lir.hotspot.aarch64.AArch64HotSpotSimdLIRKindTool;
import jdk.graal.compiler.vector.lir.hotspot.aarch64.AArch64HotSpotVectorLIRGenerator;
import jdk.internal.misc.Unsafe;
import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.CompilationRequest;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.hotspot.aarch64.AArch64HotSpotRegisterConfig;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * HotSpot AArch64 specific backend.
 */
public class AArch64HotSpotBackend extends HotSpotHostBackend implements LIRGenerationProvider {
    protected final boolean neonSupported;

    public AArch64HotSpotBackend(GraalHotSpotVMConfig config, HotSpotGraalRuntimeProvider runtime, HotSpotProviders providers) {
        super(config, runtime, providers);
        neonSupported = ((AArch64) providers.getCodeCache().getTarget().arch).getFeatures().contains(AArch64.CPUFeature.ASIMD);
    }

    @Override
    protected FrameMapBuilder newFrameMapBuilder(RegisterConfig registerConfig, Stub stub) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        FrameMap frameMap = new AArch64FrameMap(getCodeCache(), registerConfigNonNull, this);
        return new AArch64FrameMapBuilder(frameMap, getCodeCache(), registerConfigNonNull);
    }

    @Override
    public LIRGeneratorTool newLIRGenerator(LIRGenerationResult lirGenRes) {
        if (neonSupported) {
            return new AArch64HotSpotVectorLIRGenerator(
                            new AArch64HotSpotSimdLIRKindTool(),
                            new AArch64VectorArithmeticLIRGenerator(null),
                            new AArch64VectorMoveFactory(new AArch64HotSpotMoveFactory(), new MoveFactory.BackupSlotProvider(lirGenRes.getFrameMapBuilder())),
                            getProviders(),
                            config,
                            lirGenRes);
        } else {
            return new AArch64HotSpotLIRGenerator(getProviders(), config, lirGenRes);
        }
    }

    @Override
    public NodeLIRBuilderTool newNodeLIRBuilder(StructuredGraph graph, LIRGeneratorTool lirGen) {
        if (lirGen.getArithmetic() instanceof VectorLIRGeneratorTool) {
            return new AArch64HotSpotNodeLIRBuilder(graph, lirGen, new AArch64VectorNodeMatchRules(lirGen));
        } else {
            return new AArch64HotSpotNodeLIRBuilder(graph, lirGen, new AArch64NodeMatchRules(lirGen));
        }
    }

    @Override
    protected void bangStackWithOffset(CompilationResultBuilder crb, int bangOffset) {
        AArch64MacroAssembler masm = (AArch64MacroAssembler) crb.asm;
        try (ScratchRegister sc = masm.getScratchRegister()) {
            Register scratch = sc.getRegister();
            AArch64Address address = masm.makeAddress(64, sp, -bangOffset, scratch);
            masm.str(64, zr, address);
        }
    }

    @Override
    public InstalledCode createInstalledCode(DebugContext debug,
                    ResolvedJavaMethod method,
                    CompilationRequest compilationRequest,
                    CompilationResult compilationResult,
                    InstalledCode predefinedInstalledCode,
                    boolean isDefault,
                    boolean profileDeopt,
                    Object[] context) {
        boolean isStub = (method == null);
        if (!isStub) {
            // Non-stub compilation results are installed into HotSpot as nmethods. As AArch64 has
            // a constraint that the instruction at nmethod verified entry point should be a nop or
            // jump, AArch64HotSpotBackend always generate a nop placeholder before the code body
            // for non-AOT compilations. See AArch64HotSpotBackend.emitInvalidatePlaceholder(). This
            // assert checks if the nop placeholder is generated at all required places, including
            // in manually assembled code in CodeGenTest cases.
            assert hasInvalidatePlaceholder(compilationResult);
        }
        return super.createInstalledCode(debug, method, compilationRequest, compilationResult, predefinedInstalledCode, isDefault, profileDeopt, context);
    }

    private boolean hasInvalidatePlaceholder(CompilationResult compilationResult) {
        byte[] targetCode = compilationResult.getTargetCode();
        int verifiedEntryOffset = 0;
        for (CompilationResult.CodeMark mark : compilationResult.getMarks()) {
            if (mark.id == HotSpotMarkId.VERIFIED_ENTRY || mark.id == HotSpotMarkId.OSR_ENTRY) {
                // The nmethod verified entry is located at some pc offset.
                verifiedEntryOffset = mark.pcOffset;
                break;
            }
        }
        Unsafe unsafe = Unsafe.getUnsafe();
        int instruction = unsafe.getIntVolatile(targetCode, unsafe.arrayBaseOffset(byte[].class) + verifiedEntryOffset);
        AArch64MacroAssembler masm = new AArch64HotSpotMacroAssembler(getTarget(), config, heapBaseRegister);
        masm.nop();
        return instruction == masm.getInt(0);
    }

    public void rawLeave(CompilationResultBuilder crb) {
        AArch64MacroAssembler masm = (AArch64MacroAssembler) crb.asm;
        FrameMap frameMap = crb.frameMap;
        final int totalFrameSize = frameMap.totalFrameSize();
        // based on HotSpot's macroAssembler_aarch64.cpp MacroAssembler::leave_frame
        try (ScratchRegister sc = masm.getScratchRegister()) {
            int wordSize = 8;
            Register scratch = sc.getRegister();
            final int frameSize = frameMap.frameSize();
            assert totalFrameSize > 0 : totalFrameSize;
            AArch64Address.AddressingMode addressingMode = AArch64Address.AddressingMode.IMMEDIATE_PAIR_SIGNED_SCALED;
            if (AArch64Address.isValidImmediateAddress(64, addressingMode, frameSize)) {
                masm.ldp(64, fp, lr, AArch64Address.createImmediateAddress(64, addressingMode, sp, frameSize));
                masm.add(64, sp, sp, totalFrameSize);
            } else {
                int frameRecordSize = 2 * wordSize;
                masm.add(64, sp, sp, totalFrameSize - frameRecordSize, scratch);
                masm.ldp(64, fp, lr, AArch64Address.createImmediateAddress(64, AArch64Address.AddressingMode.IMMEDIATE_PAIR_POST_INDEXED, sp, frameRecordSize));
            }
            if (config.ropProtection) {
                masm.autia(lr, fp);
            }
        }
    }

    public static void rawEnter(CompilationResultBuilder crb, FrameMap frameMap, AArch64MacroAssembler masm, GraalHotSpotVMConfig config, boolean isStub) {
        // based on HotSpot's macroAssembler_aarch64.cpp MacroAssembler::build_frame
        try (ScratchRegister sc = masm.getScratchRegister()) {
            if (config.ropProtection) {
                masm.pacia(lr, fp);
            }
            final int frameSize = frameMap.frameSize();
            final int totalFrameSize = frameMap.totalFrameSize();
            int wordSize = crb.target.arch.getWordSize();
            assert frameSize + 2 * wordSize == totalFrameSize : "total framesize should be framesize + 2 words";
            Register scratch = sc.getRegister();
            assert totalFrameSize > 0 : totalFrameSize;
            AArch64Address.AddressingMode addressingMode = AArch64Address.AddressingMode.IMMEDIATE_PAIR_SIGNED_SCALED;
            if (AArch64Address.isValidImmediateAddress(64, addressingMode, frameSize)) {
                masm.sub(64, sp, sp, totalFrameSize);
                masm.stp(64, fp, lr, AArch64Address.createImmediateAddress(64, addressingMode, sp, frameSize));
                if (config.preserveFramePointer(isStub)) {
                    masm.add(64, fp, sp, frameSize);
                }
            } else {
                int frameRecordSize = 2 * wordSize;
                masm.stp(64, fp, lr, AArch64Address.createImmediateAddress(64, AArch64Address.AddressingMode.IMMEDIATE_PAIR_PRE_INDEXED, sp, -frameRecordSize));
                if (config.preserveFramePointer(isStub)) {
                    masm.mov(64, fp, sp);
                }
                masm.sub(64, sp, sp, totalFrameSize - frameRecordSize, scratch);
            }
        }
    }

    public class HotSpotFrameContext implements FrameContext {
        final boolean isStub;
        private final EntryPointDecorator entryPointDecorator;

        HotSpotFrameContext(boolean isStub, EntryPointDecorator entryPointDecorator) {
            this.isStub = isStub;
            this.entryPointDecorator = entryPointDecorator;
        }

        @Override
        public void enter(CompilationResultBuilder crb) {
            FrameMap frameMap = crb.frameMap;
            AArch64MacroAssembler masm = (AArch64MacroAssembler) crb.asm;
            if (!isStub) {
                emitStackOverflowCheck(crb);
            }
            crb.blockComment("[method prologue]");
            rawEnter(crb, frameMap, masm, config, isStub);

            crb.recordMark(HotSpotMarkId.FRAME_COMPLETE);
            if (!isStub) {
                emitNmethodEntryBarrier(crb, masm);
            }
            if (entryPointDecorator != null) {
                entryPointDecorator.emitEntryPoint(crb, false);
            }
            if (ZapStackOnMethodEntry.getValue(crb.getOptions())) {
                try (ScratchRegister sc = masm.getScratchRegister()) {
                    Register scratch = sc.getRegister();
                    int longSize = 8;
                    masm.mov(64, scratch, sp);
                    AArch64Address address = AArch64Address.createImmediateAddress(64, AArch64Address.AddressingMode.IMMEDIATE_POST_INDEXED, scratch, longSize);
                    try (ScratchRegister sc2 = masm.getScratchRegister()) {
                        Register value = sc2.getRegister();
                        masm.mov(value, 0xBADDECAFFC0FFEEL);
                        for (int i = 0; i < frameMap.frameSize(); i += longSize) {
                            masm.str(64, value, address);
                        }
                    }

                }
            }
            crb.blockComment("[code body]");
        }

        private void emitNmethodEntryBarrier(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
            try (ScratchRegister sc = masm.getScratchRegister(); ScratchRegister sc2 = masm.getScratchRegister()) {
                Register scratch1 = sc.getRegister();
                Register scratch2 = sc2.getRegister();

                GraalError.guarantee(HotSpotMarkId.ENTRY_BARRIER_PATCH.isAvailable(), "must be available");
                ForeignCallLinkage callTarget = getForeignCalls().lookupForeignCall(HotSpotHostForeignCallsProvider.NMETHOD_ENTRY_BARRIER);
                Register thread = getProviders().getRegisters().getThreadRegister();

                // The assembly sequence is from
                // src/hotspot/cpu/aarch64/gc/shared/barrierSetAssembler_aarch64.cpp. It was
                // improved in
                // JDK 20 to be more efficient.
                final Label continuation = new Label();
                final Label entryPoint = new Label();

                /*
                 * The following code sequence must be emitted in exactly this fashion as HotSpot
                 * will check that the barrier is the expected code sequence.
                 */
                crb.recordMark(HotSpotMarkId.ENTRY_BARRIER_PATCH);
                DataSection.Data data = crb.dataBuilder.createMutableData(4, 4);
                masm.ldr(32, scratch1, (AArch64Address) crb.recordDataSectionReference(data));

                if (config.BarrierSetAssembler_nmethod_patching_type == config.NMethodPatchingType_conc_instruction_and_data_patch) {
                    // If we patch code we need both a code patching and a loadload
                    // fence. It's not super cheap, so we use a global epoch mechanism
                    // to hide them in a slow path.
                    // The high level idea of the global epoch mechanism is to detect
                    // when any thread has performed the required fencing, after the
                    // last nmethod was disarmed. This implies that the required
                    // fencing has been performed for all preceding nmethod disarms
                    // as well. Therefore, we do not need any further fencing.
                    masm.mov(scratch2, config.BarrierSetAssembler_patching_epoch_addr);
                    // Embed an artificial data dependency to order the guard load
                    // before the epoch load.
                    masm.orr(64, scratch2, scratch2, scratch1, LSR, 32);
                    // Read the global epoch value.
                    masm.ldr(32, scratch2, AArch64Address.createBaseRegisterOnlyAddress(32, scratch2));
                    // Combine the guard value (low order) with the epoch value (high order).
                    masm.orr(64, scratch1, scratch1, scratch2, LSL, 32);
                    // Compare the global values with the thread-local values.
                    AArch64Address threadDisarmedAndEpochAddr = masm.makeAddress(64, thread, config.threadDisarmedOffset, scratch2);
                    masm.ldr(64, scratch2, threadDisarmedAndEpochAddr);
                    masm.cmp(64, scratch1, scratch2);

                } else {

                    if (config.BarrierSetAssembler_nmethod_patching_type == config.NMethodPatchingType_conc_data_patch) {
                        masm.dmb(AArch64Assembler.BarrierKind.LOAD_ANY);
                    }

                    AArch64Address threadDisarmedAddr = masm.makeAddress(32, thread, config.threadDisarmedOffset, scratch2);
                    masm.ldr(32, scratch2, threadDisarmedAddr);
                    masm.cmp(32, scratch1, scratch2);
                }
                masm.branchConditionally(AArch64Assembler.ConditionFlag.NE, entryPoint);
                crb.getLIR().addSlowPath(null, () -> {
                    masm.bind(entryPoint);
                    int beforeCall = masm.position();
                    if (AArch64Call.isNearCall(callTarget)) {
                        // Address is fixed up by the runtime.
                        masm.bl();
                    } else {
                        masm.movNativeAddress(scratch1, 0L, true);
                        masm.blr(scratch1);
                    }
                    crb.recordDirectCall(beforeCall, masm.position(), callTarget, null);

                    // Return to inline code
                    masm.jmp(continuation);
                });
                masm.bind(continuation);
            }
        }

        @Override
        public void leave(CompilationResultBuilder crb) {
            crb.blockComment("[method epilogue]");
            rawLeave(crb);
        }

        @Override
        public void returned(CompilationResultBuilder crb) {
            // nothing to do
        }

    }

    @Override
    public CompilationResultBuilder newCompilationResultBuilder(LIRGenerationResult lirGenRen, FrameMap frameMap, CompilationResult compilationResult, CompilationResultBuilderFactory factory,
                    EntryPointDecorator entryPointDecorator) {
        HotSpotLIRGenerationResult gen = (HotSpotLIRGenerationResult) lirGenRen;
        LIR lir = gen.getLIR();
        assert gen.getDeoptimizationRescueSlot() == null || frameMap.frameNeedsAllocating() : "method that can deoptimize must have a frame";

        Stub stub = gen.getStub();
        AArch64MacroAssembler masm = new AArch64HotSpotMacroAssembler(getTarget(), config, heapBaseRegister);
        HotSpotFrameContext frameContext = new HotSpotFrameContext(stub != null, entryPointDecorator);

        DataBuilder dataBuilder = new HotSpotDataBuilder(getCodeCache().getTarget());
        CompilationResultBuilder crb = factory.createBuilder(getProviders(), frameMap, masm, dataBuilder, frameContext, lir.getOptions(), lir.getDebug(), compilationResult,
                        Register.None, lir);
        crb.setTotalFrameSize(frameMap.totalFrameSize());
        crb.setMaxInterpreterFrameSize(gen.getMaxInterpreterFrameSize());
        crb.setMinDataSectionItemAlignment(getMinDataSectionItemAlignment());

        StackSlot deoptimizationRescueSlot = gen.getDeoptimizationRescueSlot();
        if (deoptimizationRescueSlot != null && stub == null) {
            crb.compilationResult.setCustomStackAreaOffset(deoptimizationRescueSlot);
        }

        if (stub != null) {
            updateStub(stub, gen, frameMap);
        }
        return crb;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, ResolvedJavaMethod installedCodeOwner, EntryPointDecorator entryPointDecorator) {
        crb.buildLabelOffsets();
        try {
            emitCodeHelper(crb, installedCodeOwner, entryPointDecorator);
        } catch (BranchTargetOutOfBoundsException e) {
            // A branch estimation was wrong, now retry with conservative label ranges, this
            // should always work
            crb.resetForEmittingCode();
            crb.setConservativeLabelRanges();
            emitCodeHelper(crb, installedCodeOwner, entryPointDecorator);
        }
    }

    private void emitCodeHelper(CompilationResultBuilder crb, ResolvedJavaMethod installedCodeOwner, EntryPointDecorator entryPointDecorator) {
        AArch64MacroAssembler masm = (AArch64MacroAssembler) crb.asm;
        FrameMap frameMap = crb.frameMap;
        RegisterConfig regConfig = frameMap.getRegisterConfig();
        emitCodePrefix(crb, installedCodeOwner, masm, regConfig);

        if (entryPointDecorator != null) {
            entryPointDecorator.emitEntryPoint(crb, true);
        }
        emitCodeBody(crb, masm);
        emitCodeSuffix(crb, masm);
    }

    private void emitCodePrefix(CompilationResultBuilder crb, ResolvedJavaMethod installedCodeOwner, AArch64MacroAssembler masm, RegisterConfig regConfig) {
        Label verifiedStub = new Label();
        HotSpotProviders providers = getProviders();
        if (installedCodeOwner != null && !isStatic(installedCodeOwner.getModifiers())) {
            JavaType[] parameterTypes = {providers.getMetaAccess().lookupJavaType(Object.class)};
            CallingConvention cc = regConfig.getCallingConvention(HotSpotCallingConventionType.JavaCallee, null, parameterTypes, this);
            Register receiver = asRegister(cc.getArgument(0));
            int size = config.useCompressedClassPointers ? 32 : 64;
            if (config.icSpeculatedKlassOffset == Integer.MAX_VALUE) {
                crb.recordMark(HotSpotMarkId.UNVERIFIED_ENTRY);
                Register klass = rscratch1;
                if (config.useCompressedClassPointers) {
                    if (config.useCompactObjectHeaders) {
                        ((AArch64HotSpotMacroAssembler) masm).loadCompactClassPointer(klass, receiver);
                    } else {
                        masm.ldr(size, klass, masm.makeAddress(size, receiver, config.hubOffset));
                    }
                    AArch64HotSpotMove.decodeKlassPointer(masm, klass, klass, config.getKlassEncoding());
                } else {
                    masm.ldr(size, klass, masm.makeAddress(size, receiver, config.hubOffset));
                }
                // c1_LIRAssembler_aarch64.cpp: const Register IC_Klass = rscratch2;
                Register inlineCacheKlass = AArch64HotSpotRegisterConfig.inlineCacheRegister;
                masm.cmp(64, inlineCacheKlass, klass);

                masm.branchConditionally(AArch64Assembler.ConditionFlag.EQ, verifiedStub);
                AArch64Call.directJmp(crb, masm, getForeignCalls().lookupForeignCall(IC_MISS_HANDLER));
            } else {
                // JDK-8322630 (removed ICStubs)
                Register data = AArch64HotSpotRegisterConfig.inlineCacheRegister;
                Register tmp1 = rscratch1;
                Register tmp2 = r10; // Safe to use R10 as scratch register in method prologue
                ForeignCallLinkage icMissHandler = getForeignCalls().lookupForeignCall(IC_MISS_HANDLER);

                // Size of IC check sequence checked with a guarantee below.
                int inlineCacheCheckSize = AArch64Call.isNearCall(icMissHandler) ? 20 : 32;
                if (config.useCompactObjectHeaders) {
                    // Extra instruction for shifting
                    inlineCacheCheckSize += 4;
                }
                masm.align(config.codeEntryAlignment, masm.position() + inlineCacheCheckSize);

                int startICCheck = masm.position();
                crb.recordMark(HotSpotMarkId.UNVERIFIED_ENTRY);
                AArch64Address icSpeculatedKlass = masm.makeAddress(size, data, config.icSpeculatedKlassOffset);

                if (config.useCompactObjectHeaders) {
                    ((AArch64HotSpotMacroAssembler) masm).loadCompactClassPointer(tmp1, receiver);
                } else {
                    masm.ldr(size, tmp1, masm.makeAddress(size, receiver, config.hubOffset));
                }

                masm.ldr(size, tmp2, icSpeculatedKlass);
                masm.cmp(size, tmp1, tmp2);
                masm.branchConditionally(AArch64Assembler.ConditionFlag.EQ, verifiedStub);
                AArch64Call.directJmp(crb, masm, icMissHandler);

                int actualInlineCacheCheckSize = masm.position() - startICCheck;
                if (actualInlineCacheCheckSize != inlineCacheCheckSize) {
                    // Code emission pattern has changed: adjust `inlineCacheCheckSize`
                    // initialization above accordingly.
                    throw new GraalError("%s != %s", actualInlineCacheCheckSize, inlineCacheCheckSize);
                }
            }
        }
        masm.align(config.codeEntryAlignment);
        masm.bind(verifiedStub);
        crb.recordMark(crb.compilationResult.getEntryBCI() != -1 ? HotSpotMarkId.OSR_ENTRY : HotSpotMarkId.VERIFIED_ENTRY);
    }

    private static void emitCodeBody(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        emitInvalidatePlaceholder(crb, masm);
        crb.emitLIR();
    }

    /**
     * Insert a nop at the start of the prolog so we can patch in a branch if we need to invalidate
     * the method later.
     *
     * @see "http://mail.openjdk.java.net/pipermail/aarch64-port-dev/2013-September/000273.html"
     */
    public static void emitInvalidatePlaceholder(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        crb.blockComment("[nop for method invalidation]");
        masm.nop();
    }

    private void emitCodeSuffix(CompilationResultBuilder crb, AArch64MacroAssembler masm) {
        HotSpotProviders providers = getProviders();
        HotSpotFrameContext frameContext = (HotSpotFrameContext) crb.frameContext;
        if (!frameContext.isStub) {
            HotSpotForeignCallsProvider foreignCalls = providers.getForeignCalls();
            if (crb.getPendingImplicitExceptionList() != null) {
                try (ScratchRegister sc = masm.getScratchRegister()) {
                    Register scratch = sc.getRegister();
                    for (CompilationResultBuilder.PendingImplicitException pendingImplicitException : crb.getPendingImplicitExceptionList()) {
                        // Insert stub code that stores the corresponding deoptimization action &
                        // reason, as well as the failed speculation, and calls into
                        // DEOPT_BLOB_UNCOMMON_TRAP. Note that we use the debugging info at the
                        // exceptional PC that triggers this implicit exception, we cannot touch
                        // any register/stack slot in this stub, so as to preserve a valid mapping
                        // for constructing the interpreter frame.
                        int pos = masm.position();
                        Register thread = getProviders().getRegisters().getThreadRegister();
                        // Store deoptimization reason and action into thread local storage.
                        int dwordSizeInBits = AArch64Kind.DWORD.getSizeInBytes() * Byte.SIZE;
                        AArch64Address pendingDeoptimization = AArch64Address.createImmediateAddress(dwordSizeInBits, IMMEDIATE_UNSIGNED_SCALED, thread, config.pendingDeoptimizationOffset);
                        masm.mov(scratch, pendingImplicitException.state.deoptReasonAndAction.asInt());
                        masm.str(dwordSizeInBits, scratch, pendingDeoptimization);

                        // Store speculation into thread local storage
                        JavaConstant deoptSpeculation = pendingImplicitException.state.deoptSpeculation;
                        if (deoptSpeculation.getJavaKind() == JavaKind.Long) {
                            int qwordSizeInBits = AArch64Kind.QWORD.getSizeInBytes() * Byte.SIZE;
                            AArch64Address pendingSpeculation = AArch64Address.createImmediateAddress(qwordSizeInBits, IMMEDIATE_UNSIGNED_SCALED, thread, config.pendingFailedSpeculationOffset);
                            masm.mov(scratch, pendingImplicitException.state.deoptSpeculation.asLong());
                            masm.str(qwordSizeInBits, scratch, pendingSpeculation);
                        } else {
                            assert deoptSpeculation.getJavaKind() == JavaKind.Int : deoptSpeculation;
                            AArch64Address pendingSpeculation = AArch64Address.createImmediateAddress(dwordSizeInBits, IMMEDIATE_UNSIGNED_SCALED, thread, config.pendingFailedSpeculationOffset);
                            masm.mov(scratch, pendingImplicitException.state.deoptSpeculation.asInt());
                            masm.str(dwordSizeInBits, scratch, pendingSpeculation);
                        }

                        ForeignCallLinkage uncommonTrapBlob = foreignCalls.lookupForeignCall(DEOPT_BLOB_UNCOMMON_TRAP);
                        Register helper = AArch64Call.isNearCall(uncommonTrapBlob) ? null : scratch;
                        AArch64Call.directCall(crb, masm, uncommonTrapBlob, helper, pendingImplicitException.state);
                        crb.recordImplicitException(pendingImplicitException.codeOffset, pos, pendingImplicitException.state);
                    }
                }
            }
            try (ScratchRegister sc = masm.getScratchRegister()) {
                Register scratch = sc.getRegister();
                crb.recordMark(HotSpotMarkId.EXCEPTION_HANDLER_ENTRY);
                ForeignCallLinkage linkage = foreignCalls.lookupForeignCall(EXCEPTION_HANDLER);
                Register helper = AArch64Call.isNearCall(linkage) ? null : scratch;
                AArch64Call.directCall(crb, masm, linkage, helper, null);
                // Ensure the return location is a unique pc and that control flow doesn't return
                // here
                masm.halt();
            }
            crb.recordMark(HotSpotMarkId.DEOPT_HANDLER_ENTRY);
            ForeignCallLinkage linkage = foreignCalls.lookupForeignCall(DEOPT_BLOB_UNPACK);
            masm.adr(lr, 0); // Warning: the argument is an offset from the instruction!
            AArch64Call.directJmp(crb, masm, linkage);
            if (config.supportsMethodHandleDeoptimizationEntry() && crb.needsMHDeoptHandler()) {
                crb.recordMark(HotSpotMarkId.DEOPT_MH_HANDLER_ENTRY);
                masm.adr(lr, 0);
                AArch64Call.directJmp(crb, masm, linkage);
            }
        }
    }

    @Override
    public RegisterAllocationConfig newRegisterAllocationConfig(RegisterConfig registerConfig, String[] allocationRestrictedTo, Object stub) {
        RegisterConfig registerConfigNonNull = registerConfig == null ? getCodeCache().getRegisterConfig() : registerConfig;
        return new AArch64HotSpotRegisterAllocationConfig(registerConfigNonNull, allocationRestrictedTo, config.preserveFramePointer(stub != null));
    }
}
