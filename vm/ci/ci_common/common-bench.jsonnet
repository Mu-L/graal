# note: this file needs to be in sync between CE and EE

local utils = import '../../../ci/ci_common/common-utils.libsonnet';
local vm = import '../ci_includes/vm.jsonnet';
local common = import '../../../ci/ci_common/common.jsonnet';
local vm_common = import '../ci_common/common.jsonnet';

local repo_config = import '../../../ci/repo-configuration.libsonnet';

{
  vm_bench_base(machine_name=null)::
    {
      result_file:: 'results.json',
      upload:: ['bench-uploader.py', self.result_file],
      upload_and_wait_for_indexing:: self.upload + ['--wait-for-indexing'],
      timelimit: '1:30:00',
      capabilities+: if std.objectHasAll(self, 'machine_name') then [self.machine_name] else [],
    } +
    (if machine_name != null then {
      machine_name:: machine_name,
      capabilities+: [machine_name]
    } else {}),

  vm_bench_common: self.vm_bench_base(machine_name='x52') + { capabilities+: ['tmpfs25g'] },

  vm_bench_js_linux_amd64(bench_suite=null): vm.vm_java_Latest + common.deps.svm + common.deps.sulong + vm.custom_vm + self.vm_bench_common + {
    cmd_base:: vm_common.mx_vm_common + ['--dynamicimports', 'js-benchmarks', 'benchmark', '--results-file', self.result_file],
    config_base:: ['--js-vm=graal-js', '--js-vm-config=default', '--jvm=graalvm-${VM_ENV}'],
    setup+: [
      ['set-export', 'VM_ENV', '$VM_ENV-js'],
      vm_common.mx_vm_common + ['build'],
      ['git', 'clone', '--depth', '1', ['mx', 'urlrewrite', 'https://github.com/graalvm/js-benchmarks.git'], '../../js-benchmarks'],
    ],
    run+:
      if (bench_suite != null) then [
        self.cmd_base + [bench_suite + ':*', '--'] + self.config_base + ['--jvm-config=jvm', '--vm.Xss24m'],
        $.vm_bench_common.upload,
        self.cmd_base + [bench_suite + ':*', '--'] + self.config_base + ['--jvm-config=native'],
        $.vm_bench_common.upload,
      ] else [],
  },

  polybench_hpc_linux_common(shape=null):
    (if shape != null then {
      machine_name:: shape,
      capabilities+: [shape],
    } else {}) + {
    packages+: {
      'papi': '==5.5.1',
    },
    environment+: {
      ENABLE_POLYBENCH_HPC: 'yes',
      POLYBENCH_HPC_EXTRA_HEADERS: '/cm/shared/apps/papi/papi-5.5.1/include',
      POLYBENCH_HPC_PAPI_LIB_DIR: '/cm/shared/apps/papi/papi-5.5.1/lib',
    } + if !std.objectHasAll(self, 'machine_name') then {} else if std.count(['e3', 'e4_36_256', 'e4_8_64'], self.machine_name) > 0 then {LIBPFM_FORCE_PMU: 'amd64'} else if self.machine_name == 'x52' then {} else {},
  },

  vm_bench_polybenchmarks_base(env): {
    base_cmd:: ['mx', '--env', env, '--dy', 'polybenchmarks'],
  },

  vm_bench_polybenchmarks_linux_build: common.deps.svm + common.deps.truffleruby + common.deps.graalpy + vm.custom_vm + vm.vm_java_Latest + self.polybench_hpc_linux_common(shape='e4_8_64') + self.vm_bench_polybenchmarks_base(env='polybench-${VM_ENV}') + {
    setup+: [
      self.base_cmd + ['sforceimports'],
    ],
    run+: [
      self.base_cmd + ['build'],
    ],
    publishArtifacts+: [
      {
        name: "graalvm-with-polybench",
        dir: "..",
        patterns: [
          "*/*/mxbuild/*",
          "*/mxbuild/*",
        ]
      }
    ],
    timelimit: '1:00:00',
  },

  # TODO (GR-60584): re-enable espresso polybench jobs once polybench is unchained
  vm_bench_polybenchmarks_linux_common(vm_config='jvm', is_gate=false, suite='default:~r[.*jar]', shape=null): common.deps.svm + common.deps.truffleruby + vm.custom_vm + vm.vm_java_Latest + self.polybench_hpc_linux_common(shape=shape) + self.vm_bench_polybenchmarks_base(env='polybench-${VM_ENV}') + (if is_gate then self.vm_bench_base(machine_name=null) else self.vm_bench_common) + {
    bench_cmd:: self.base_cmd + ['benchmark', '--results-file', self.result_file],
    setup+: [
      self.base_cmd + ['sforceimports'],
      ['unpack-artifact', 'graalvm-with-polybench'],
      ['mx', '-p', '../../polybenchmarks/', 'build_benchmarks'],
    ],
    requireArtifacts: [{
      name: 'graalvm-with-polybench',
      autoExtract: false,
      dir: '..',
    }],
    run+: if (is_gate) then  [
      # TODO (GR-60584): re-enable espresso polybench jobs once polybench is unchained
      self.bench_cmd + ['polybenchmarks-awfy:~r[.*jar]',    '--', '--polybenchmark-vm=graalvm-${VM_ENV}', '--polybenchmark-vm-config=native', '--gate'],
      self.bench_cmd + ['polybenchmarks-awfy:~r[.*jar]',    '--', '--polybenchmark-vm=graalvm-${VM_ENV}', '--polybenchmark-vm-config=jvm',    '--gate'],
      self.bench_cmd + ['polybenchmarks-default:~r[.*jar]', '--', '--polybenchmark-vm=graalvm-${VM_ENV}', '--polybenchmark-vm-config=native', '--gate'],
      self.bench_cmd + ['polybenchmarks-default:~r[.*jar]', '--', '--polybenchmark-vm=graalvm-${VM_ENV}', '--polybenchmark-vm-config=jvm',    '--gate'],
    ] else [
      self.bench_cmd + ['polybenchmarks-' + suite, '--', '--polybenchmark-vm=graalvm-${VM_ENV}', '--polybenchmark-vm-config=' + vm_config],
    ],
    notify_emails+: if (is_gate) then [] else [ 'boris.spasojevic@oracle.com' ],
    teardown+:      if (is_gate) then [] else [ $.vm_bench_common.upload ],
    timelimit:      if (is_gate) then '1:00:00' else '1:30:00',
  },

  vm_bench_polybench_linux_common(env='polybench-${VM_ENV}', fail_fast=false, skip_machine=false): (if skip_machine then self.vm_bench_base(machine_name=null) else self.vm_bench_common) + common.deps.svm + common.deps.truffleruby + common.deps.graalpy + common.deps.wasm + vm.custom_vm {
    base_cmd:: ['mx', '--env', env],
    bench_cmd:: self.base_cmd + ['benchmark'] + (if (fail_fast) then ['--fail-fast'] else []),
    interpreter_bench_cmd(vmConfig):: self.bench_cmd +
        (if std.startsWith(vmConfig, 'jvm-') && self.jdk_version >= 22 then
            ['polybench:~r[(compiler/.*)|(warmup/.*)|(wasm-simd/.*)]']
        else
            ['polybench:~r[(compiler/.*)|(warmup/.*)|(.*panama.*)|(wasm-simd/.*)]'] # panama NFI backend only supported in JVM mode and on JDK 22+ [GR-49655]
        ) + ['--results-file', self.result_file, '--', '--polybench-vm=graalvm-${VM_ENV}', '--polybench-vm-config=' + vmConfig],
    compiler_bench_cmd(vmConfig):: self.bench_cmd + ['polybench:*[compiler/dispatch.js]', '--results-file', self.result_file, '--', '--polybench-vm=graalvm-${VM_ENV}', '--polybench-vm-config=' + vmConfig],
    warmup_bench_cmd(vmConfig):: self.bench_cmd + ['--fork-count-file', 'ci/ci_common/benchmark-forks.json',  'polybench:r[warmup/.*]', '--results-file', self.result_file, '--', '--polybench-vm=graalvm-${VM_ENV}', '--polybench-vm-config=' + vmConfig],

    setup+: [
      self.base_cmd + ['build'],
      self.base_cmd + ['build', '--dependencies=POLYBENCH_BENCHMARKS'],
    ],
    notify_groups:: ['polybench'],
  },

  vm_bench_polybench_hpc_linux_common(env, metric, benchmarks='*', polybench_vm_config='native-interpreter'): self.polybench_hpc_linux_common(shape='e4_8_64') + self.vm_bench_polybench_linux_common(env=env, fail_fast=false, skip_machine=true) + {
    machine_name_prefix:: "gate-",
    run+: [
      self.base_cmd + ['benchmark', 'polybench:'+benchmarks,
                       '--fork-count-file', 'ci/ci_includes/polybench-hpc.json',
                       '--results-file', self.result_file,
                       '--machine-name', self.machine_name_prefix + self.machine_name,
                       '--',
                       '--metric=' + metric,
                       '--polybench-vm=graalvm-${VM_ENV}',
                       '--polybench-vm-config=' + polybench_vm_config],
      self.upload_and_wait_for_indexing + ['||', 'echo', 'Result upload failed!'],
    ],
  },

  vm_bench_polybench_linux_interpreter: self.vm_bench_polybench_linux_common() + vm.vm_java_Latest + {
    run+: [
      self.interpreter_bench_cmd(vmConfig='jvm-interpreter'),
      self.upload,
      self.interpreter_bench_cmd(vmConfig='native-interpreter'),
      self.upload,
    ],
    timelimit: '2:30:00',
  },

  vm_bench_polybench_linux_compiler: self.vm_bench_polybench_linux_common() + vm.vm_java_Latest + {
    compiler_bench_cmd(vmConfig):: super.compiler_bench_cmd(vmConfig) + ['-w', '0', '-i', '10'],
    run+: [
      self.compiler_bench_cmd(vmConfig='jvm-standard') + ['--metric=compilation-time'],
      self.upload,
      self.compiler_bench_cmd(vmConfig='native-standard') + ['--metric=compilation-time'],
      self.upload,
      self.compiler_bench_cmd(vmConfig='jvm-standard') + ['--metric=partial-evaluation-time'],
      self.upload,
      self.compiler_bench_cmd(vmConfig='native-standard') + ['--metric=partial-evaluation-time'],
      self.upload,
    ],
  },

  vm_bench_polybench_linux_context_init: self.vm_bench_polybench_linux_common() + vm.vm_java_Latest + {
    bench_cmd:: super.base_cmd + ['benchmark', '--fork-count-file', 'ci/ci_common/benchmark-forks.json', 'polybench:*[interpreter/pyinit.py,interpreter/jsinit.js,interpreter/rbinit.rb]', '--results-file', self.result_file, '--', '-w', '0', '-i', '0', '--polybench-vm=graalvm-${VM_ENV}'],
    run+: [
      self.bench_cmd + ['--polybench-vm-config=jvm-standard', '--metric=none'],
      self.upload,
      self.bench_cmd + ['--polybench-vm-config=native-standard', '--metric=none'],
      self.upload,
    ],
  },

  vm_bench_polybench_linux_warmup: self.vm_bench_polybench_linux_common() + vm.vm_java_Latest + {
    run+: [
      self.warmup_bench_cmd(vmConfig='native-standard') + ['--metric=one-shot'],
      self.upload,
    ],
  },

  vm_bench_polybench_linux_memory: self.vm_bench_polybench_linux_common() + vm.vm_java_Latest + {
    run+: [
      self.interpreter_bench_cmd(vmConfig='jvm-standard') + ['--metric=metaspace-memory'],
      self.upload,
      self.interpreter_bench_cmd(vmConfig='jvm-standard') + ['--metric=application-memory'],
      self.upload,
      # We run the interprer benchmarks in both interprer and standard mode to compare allocation with and without compilation.
      self.interpreter_bench_cmd(vmConfig='jvm-interpreter') + ['-w', '40', '-i', '10', '--metric=allocated-bytes'],
      self.upload,
      self.interpreter_bench_cmd(vmConfig='jvm-standard') + ['-w', '40', '-i', '10', '--metric=allocated-bytes'],
      self.upload,
      self.interpreter_bench_cmd(vmConfig='native-interpreter') + ['-w', '40', '-i', '10', '--metric=allocated-bytes'],
      self.upload,
      self.interpreter_bench_cmd(vmConfig='native-standard') + ['-w', '40', '-i', '10', '--metric=allocated-bytes'],
      self.upload,
    ],
    timelimit: '4:00:00',
  },

  vm_gate_polybench_linux: self.vm_bench_polybench_linux_common(fail_fast=true, skip_machine=true) + vm.vm_java_Latest + {
    interpreter_bench_cmd(vmConfig):: super.interpreter_bench_cmd(vmConfig) + ['-w', '1', '-i', '1'],
    compiler_bench_cmd(vmConfig):: super.compiler_bench_cmd(vmConfig) + ['-w', '0', '-i', '1'],
    warmup_bench_cmd(vmConfig):: super.warmup_bench_cmd(vmConfig) + ['-w', '1', '-i', '1'],
    run+: [
      self.interpreter_bench_cmd(vmConfig='jvm-interpreter'),
      self.interpreter_bench_cmd(vmConfig='native-interpreter'),
      self.compiler_bench_cmd(vmConfig='jvm-standard') + ['--metric=compilation-time'],
      self.compiler_bench_cmd(vmConfig='native-standard') + ['--metric=partial-evaluation-time'],
      self.warmup_bench_cmd(vmConfig='native-standard') + ['--metric=one-shot'],
    ],
    timelimit: '1:00:00',
    notify_groups: ['polybench'],
  },

  vm_bench_polybench_nfi: {
    base_cmd:: ['mx', '--env', 'polybench-nfi-${VM_ENV}'],
    local nfi_panama = 'polybench:r[nfi/.*]',
    local nfi_no_panama = 'polybench:r[nfi/(downcall|upcall)_(many|prim|simple|env|void).*]',
    # Panama is only supported on JDK 22+
    local nfi_jvm = if self.jdk_version <= 22 then nfi_no_panama else nfi_panama,
    # Panama is not supported on native-image, once supported we use nfi_jvm (GR-46740)
    local nfi_ni = nfi_no_panama,
    bench_cmd_jvm::    self.base_cmd + ['benchmark', nfi_jvm, '--results-file', self.result_file, '--', '--polybench-vm=graalvm-${VM_ENV}', '--polybench-vm-config=jvm-standard'],
    bench_cmd_native:: self.base_cmd + ['benchmark', nfi_ni,  '--results-file', self.result_file, '--', '--polybench-vm=graalvm-${VM_ENV}', '--polybench-vm-config=native-standard'],
    setup+: [
      self.base_cmd + ['build'],
      self.base_cmd + ['build', '--dependencies=POLYBENCH_BENCHMARKS'],
    ],
    run+: [
      self.bench_cmd_jvm,
      self.upload,
      self.bench_cmd_native,
      self.upload,
    ],
    notify_groups:: ['sulong'],
    timelimit: '55:00',
  },

  js_bench_compilation_throughput(pgo): self.vm_bench_common + common.heap.default + {
    local mx_libgraal = ["mx", "--env", repo_config.vm.mx_env.libgraal],

    setup+: [
      mx_libgraal + ["--dynamicimports", "/graal-js", "sforceimports"],  # clone the revision of /graal-js imported by /vm
      ["git", "clone", "--depth", "1", ['mx', 'urlrewrite', "https://github.com/graalvm/js-benchmarks.git"], "../../js-benchmarks"],
      mx_libgraal + ["--dynamicimports", "/graal-js,js-benchmarks", "sversions"]
    ] + (if pgo then repo_config.compiler.collect_libgraal_profile(mx_libgraal) else []) + [
      mx_libgraal + (if pgo then repo_config.compiler.use_libgraal_profile else []) + ["--dynamicimports", "/graal-js,js-benchmarks", "build", "--force-javac"]
    ],
    local xms = if std.objectHasAll(self.environment, 'XMS') then ["-Xms${XMS}"] else [],
    local xmx = if std.objectHasAll(self.environment, 'XMX') then ["-Xmx${XMX}"] else [],
    run: [
      mx_libgraal + ["--dynamicimports", "js-benchmarks,/graal-js",
        "benchmark", "octane:typescript",
        "--results-file", self.result_file, "--"
      ] + xms + xmx + [
        "--experimental-options",
        "--engine.CompilationFailureAction=ExitVM",
        "--engine.MaximumCompilations=200", # GR-61670
        "-Djdk.graal.DumpOnError=true",
        "-Djdk.graal.PrintGraph=File",
        "--js-vm=graal-js",
        "--js-vm-config=default",
        "--jvm=server",
        "--jvm-config=" + repo_config.compiler.libgraal_jvm_config(pgo) + "-no-truffle-bg-comp",
        "-XX:+CITime"],
      self.upload
    ],
    logs+: [
      "runtime-graphs-*.bgv"
    ],
    timelimit: "2:30:00",
    notify_groups:: ['compiler_bench']
  },

  vm_bench_polybench_nfi_linux_amd64: self.vm_bench_common + common.deps.svm + self.vm_bench_polybench_nfi,

  local builds = [
    # We used to expand `${common_vm_linux}` here to work around some limitations in the version of pyhocon that we use in the CI
    vm_common.vm_base('linux', 'amd64', 'ondemand', bench=true) + self.vm_bench_js_linux_amd64('octane')     + {name: 'ondemand-bench-vm-' + vm.vm_setup.short_name + '-js-octane-' + utils.jdk_and_hardware(self)},
    vm_common.vm_base('linux', 'amd64', 'ondemand', bench=true) + self.vm_bench_js_linux_amd64('jetstream')  + {name: 'ondemand-bench-vm-' + vm.vm_setup.short_name + '-js-jetstream-' + utils.jdk_and_hardware(self)},
    vm_common.vm_base('linux', 'amd64', 'ondemand', bench=true) + self.vm_bench_js_linux_amd64('jetstream2') + {name: 'ondemand-bench-vm-' + vm.vm_setup.short_name + '-js-jetstream2-' + utils.jdk_and_hardware(self)},
    vm_common.vm_base('linux', 'amd64', 'ondemand', bench=true) + self.vm_bench_js_linux_amd64('micro')      + {name: 'ondemand-bench-vm-' + vm.vm_setup.short_name + '-js-micro-' + utils.jdk_and_hardware(self)},
    vm_common.vm_base('linux', 'amd64', 'ondemand', bench=true) + self.vm_bench_js_linux_amd64('v8js')       + {name: 'ondemand-bench-vm-' + vm.vm_setup.short_name + '-js-v8js-' + utils.jdk_and_hardware(self)},
    vm_common.vm_base('linux', 'amd64', 'ondemand', bench=true) + self.vm_bench_js_linux_amd64('misc')       + {name: 'ondemand-bench-vm-' + vm.vm_setup.short_name + '-js-misc-' + utils.jdk_and_hardware(self)},
    vm_common.vm_base('linux', 'amd64', 'ondemand', bench=true) + self.vm_bench_js_linux_amd64('npm-regex')  + {name: 'ondemand-bench-vm-' + vm.vm_setup.short_name + '-js-npm-regex-' + utils.jdk_and_hardware(self)},

    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybench_linux_interpreter     + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybench-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybench_linux_compiler        + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybench-compiler-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybench_linux_context_init    + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybench-context-init-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybench_linux_warmup          + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybench-warmup-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybench_linux_memory          + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybench-memory-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench'] },

    # Produces the graalvm-with-polybench artifact
    vm_common.vm_base('linux', 'amd64', 'ondemand') + self.vm_bench_polybenchmarks_linux_build + {name: 'ondemand-vm-build-' + vm.vm_setup.short_name + '-with-polybench-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},

    # Consume the graalvm-with-polybench artifact
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybenchmarks_linux_common(vm_config='native')                        + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybenchmarks-default-native-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybenchmarks_linux_common(vm_config='jvm')                           + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybenchmarks-default-jvm-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybenchmarks_linux_common(vm_config='native', suite='awfy:r[.*py]')  + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybenchmarks-awfy-py-native-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybenchmarks_linux_common(vm_config='jvm',    suite='awfy:r[.*py]')  + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybenchmarks-awfy-py-jvm-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybenchmarks_linux_common(vm_config='native', suite='awfy:r[.*rb]')  + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybenchmarks-awfy-rb-native-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybenchmarks_linux_common(vm_config='jvm',    suite='awfy:r[.*rb]')  + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybenchmarks-awfy-rb-jvm-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    # TODO (GR-60584): re-enable espresso polybench jobs once polybench is unchained
    # vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybenchmarks_linux_common(vm_config='native', suite='awfy:r[.*jar]') + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybenchmarks-awfy-jar-native-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},
    # vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybenchmarks_linux_common(vm_config='jvm',    suite='awfy:r[.*jar]') + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybenchmarks-awfy-jar-jvm-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},

    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_polybench_nfi_linux_amd64 + vm.vm_java_Latest + {name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-polybench-nfi-jdk-latest-' + utils.jdk_and_hardware(self), notify_groups:: ['polybench']},

    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.js_bench_compilation_throughput(true) + vm.vm_java_Latest + { name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-libgraal-pgo-throughput-js-typescript-' + utils.jdk_and_hardware(self) },
    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.js_bench_compilation_throughput(false) + vm.vm_java_Latest + { name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-libgraal-no-pgo-throughput-js-typescript-' + utils.jdk_and_hardware(self) },

    vm_common.vm_base('linux', 'amd64', 'daily', bench=true) + self.vm_bench_js_linux_amd64() + {
      # Override `self.vm_bench_js_linux_amd64.run`
      run: [
        vm_common.mx_vm_common + ['benchmark', '--results-file', self.result_file, 'agentscript-graal-js:*', '--', '--jvm=graalvm-${VM_ENV}', '--jvm-config=jvm', '--js=graal-js', '--js-config=default'],
        $.vm_bench_common.upload,
        vm_common.mx_vm_common + ['benchmark', '--results-file', self.result_file, 'agentscript-graal-js:*', '--', '--jvm=graalvm-${VM_ENV}', '--jvm-config=native', '--js=graal-js', '--js-config=default'],
        $.vm_bench_common.upload,
      ],
      timelimit: '45:00',
      name: 'daily-bench-vm-' + vm.vm_setup.short_name + '-agentscript-js-' + utils.jdk_and_hardware(self),
      notify_groups:: ['javascript'],
    },

    vm_common.vm_base('linux', 'amd64', 'tier3') + self.vm_bench_polybenchmarks_linux_common(is_gate=true, shape='e4_8_64')    + {name: 'gate-vm-' + vm.vm_setup.short_name + '-polybenchmarks-' + utils.jdk_and_hardware(self)},
    vm_common.vm_base('linux', 'amd64', 'tier3') + self.vm_gate_polybench_linux + {name: 'gate-vm-' + vm.vm_setup.short_name + '-polybench-' + utils.jdk_and_hardware(self)},
  ],

  builds: utils.add_defined_in(builds, std.thisFile),
}
