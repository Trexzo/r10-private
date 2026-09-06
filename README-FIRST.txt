VapeTrainer BestOfBoth V3 SMART5-R10

HOW TO RUN
----------
1. Start the normal Fabric Minecraft 1.21.11 instance and wait for the menu/world.
2. Extract this ZIP completely.
3. Run VapeTrainer-Injector.exe from the extracted folder.
4. Use Combat -> BlockHit -> Smart.

R10 preserves the proven RACEGUARD1 base, FeaturePack R4, and ESP Freelook FIX4.
Only the Smart implementation/installer changed from R9.

R10 can migrate an already-running R9/RACEGUARD1 Minecraft session: the launcher
reuses the mapped base, R10 tombstones the loaded V3 Smart implementation, removes
old Smart listeners/submodules/selections, and installs a fresh SmartBlockHitModeV4.
Do not stack this onto a Minecraft process containing a different non-RACEGUARD Vape DLL.

SMART5 / R10 CORRECTIONS
------------------------
- New runtime identity: gg.vape.module.combat.blockhit.SmartBlockHitModeV4.
- A sharp hurtTime reset (normally back near 10) starts a new damage epoch.
- A health drop during that hurtTime epoch is merged as confirmation instead of
  starting a duplicate epoch, including delayed health feedback after the hurt reset.
- timeUntilRegen rises are diagnostic only. They never start an R10 damage epoch and
  therefore health regeneration cannot create fake damage scheduling by itself.
- Once an epoch establishes hurtTime, that epoch remains hurtTime-owned through zero.
  It never falls over to local500ms just because hurtTime reached zero.
- local500ms is used only when no usable hurtTime cycle establishes after a short
  100 ms grace period.
- Hurt-window release is boundary/deadline driven. Maximum Hold remains the failsafe.
- The PreTick hold guard checks one tick early to compensate for ~50 ms event
  quantization instead of routinely overshooting a configured 125 ms hold to 150+ ms.
- Same-target first-hit rearm still requires a confirmed range exit + re-entry.
- 350 ms target-loss grace and all eight existing Smart settings are preserved.
- Manual / Predict / Smart / Auto / Lag remain available; Predict is unchanged.
- Normal use-item KeyBinding only: no packet buffering, Blink/FakeLag queue,
  deliberate packet cancellation, desync, or anti-cheat bypass behavior in Smart.

RUNTIME VERIFICATION
--------------------
When Smart was already selected and BlockHit is enabled during install, the R10 agent
requires all of the following before RESULT=PASS:
- exactly one live Smart submodule;
- live class is SmartBlockHitModeV4;
- the Smart selection points to that exact V4 object;
- V4 is selected/enabled;
- smart-blockhit.log contains the SMART5-R10 onEnable marker.

Diagnostics:
%LOCALAPPDATA%\VapeTrainer\smart-blockhit.log

Launcher log:
%LOCALAPPDATA%\VapeTrainer\bestofboth-v3-smart5-r10\launcher.log

FIRST TEST
----------
Keep the R9 settings you were using. In one clean fight, check that:
1. one hurtTime reset creates one damageEpochStart;
2. delayed health-drop lines say damageSignalMerged, not another epoch start;
3. health regeneration can show regenDiagnosticRise but no damageEpochStart;
4. the clock stays clock=hurtTime through the boundary (no late local500ms switch);
5. hurtWindowEnd normally reports boundary or the guarded failsafe, with actualHoldMs
   materially closer to the configured Maximum Hold than R9's 145-174 ms samples.
