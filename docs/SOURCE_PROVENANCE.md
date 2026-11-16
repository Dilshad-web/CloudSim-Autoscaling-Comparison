# Source and result provenance

The following user-supplied archive files were used to assemble the source and result snapshots in this repository. These SHA-256 values identify the uploaded archives, not a compiled executable or a Git commit. The historical experiment outputs do not include a build hash or source commit identifier.

- **S-Threshold source package (user-provided v78-era source archive)**
  File: `rquired for gpt.zip`; size: 2,622,895 bytes; SHA-256: `83b9b5b30f028142f375e20e6c4c06e1329def6631c3d9cf6eed7aa2ad4bbf7b`
- **S-Threshold source update archive**
  File: `v78-update-2.zip`; size: 29,147 bytes; SHA-256: `68e18f6d877fc14884c1de6c94bf85d72595a9328427bb61c20e48eff460fabd`
- **S-Threshold result archive**
  File: `results.zip`; size: 2,554,303 bytes; SHA-256: `033fdf09292e22ffb2d6dc37ff3e6c24aa166aada17b48e2b14a945df638191a`
- **Q-Threshold stabilized source archive (raw Bitbrains CSVs excluded from repository)**
  File: `QThreshold-CloudSim-Plus-30Day-Stability-Fix.zip`; size: 134,239,378 bytes; SHA-256: `e6ec4f94555480107bf3ee7a0a5e57281fd19a19d54fcd4aab01c65162238422`
- **Q-Threshold stabilized-v2 result archive**
  File: `results_qthreshold2(1).zip`; size: 336,208 bytes; SHA-256: `4b399788d692dec9e354ac93d249b5fb43cbbea3c377ca0155ef2a712ab45cbc`

## Assembly notes

- The S-Threshold source tree was extracted from the supplied `rquired for gpt.zip`; the files in `v78-update-2.zip` were applied where present. The two patched runner files match the corresponding files in the supplied project archive byte-for-byte.
- The S-Threshold selected result snapshot comes from the root `results/` content of `results.zip`; older archived result generations were not included.
- The Q-Threshold source tree was extracted from `QThreshold-CloudSim-Plus-30Day-Stability-Fix.zip`; the 1,250 raw Bitbrains CSVs were excluded.
- The Q-Threshold result snapshot comes from `results_qthreshold2(1).zip`; raw/derived trace caches and the trace-used time series were excluded.
- The S-Threshold POM and launch scripts were made Maven-based for a portable repository handoff; the Java controller source was not changed by that packaging adjustment. These convenience scripts were not executed in this environment because Maven is unavailable.
- No claim is made that these archive hashes identify the exact executable build used to produce historical result files.
