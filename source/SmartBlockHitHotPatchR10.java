package vtsmart10;

import java.io.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;

/**
 * R10 installer: new SmartBlockHitModeV4 identity + hard cleanup of stale Smart
 * selections/submodules/listeners. Loaded V2/V3 are tombstoned so orphan listeners
 * from earlier live-patch revisions cannot continue driving use-item.
 */
public final class SmartBlockHitHotPatchR10 {
    private static final String V4 = "gg.vape.module.combat.blockhit.SmartBlockHitModeV4";
    private static final String V3 = "gg.vape.module.combat.blockhit.SmartBlockHitModeV3";
    private static final String V4_STATE = V4 + "$State";
    private static final String V4_CLOCK = V4 + "$Clock";
    private static final String V2 = "gg.vape.module.combat.blockhit.SmartBlockHitModeV2";
    private static final String MARKER = "SMART5-R10 enabled";

    private static final String[] VALUE_FIELDS = new String[]{
        "targetAngle", "targetSearchDistance", "blockRange", "maximumHurtTime",
        "includePing", "maximumHoldDuration", "blockFirstHit", "requireApproaching"
    };

    public static void agentmain(String args, Instrumentation inst) {
        List<String> out = new ArrayList<String>();
        String statusPath = args == null ? "" : args.trim();
        try {
            out.add("PATCH=SMART-BLOCKHIT-LIVE-R10-SMART5-CLEAN-V4");
            Class<?> blockHitClass = findLoaded(inst, "gg.vape.module.combat.BlockHit");
            if (blockHitClass == null) throw new IllegalStateException("BlockHit is not loaded yet; Vape bootstrap is not ready.");
            ClassLoader loader = blockHitClass.getClassLoader();
            out.add("loader=" + loader);

            Class<?> blockHitModeClass = Class.forName("gg.vape.module.combat.blockhit.BlockHitMode", false, loader);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(blockHitModeClass, MethodHandles.lookup());

            // Disable the prior V2 implementation at the bytecode level if it exists.
            Class<?> loadedV2 = findLoaded(inst, V2);
            if (loadedV2 != null) {
                if (!inst.isModifiableClass(loadedV2)) throw new IllegalStateException("Loaded V2 Smart class is not modifiable.");
                inst.redefineClasses(new ClassDefinition(loadedV2, readResource("/payload/SmartBlockHitModeV2.class")));
                out.add("legacyV2Tombstone=PASS class=" + V2);
            } else {
                out.add("legacyV2Tombstone=not-loaded");
            }

            // R10 also tombstones the active R9 V3 bytecode before unregistering old
            // Smart instances, so a stale cached listener cannot keep driving input.
            Class<?> loadedV3 = findLoaded(inst, V3);
            if (loadedV3 != null) {
                if (!inst.isModifiableClass(loadedV3)) throw new IllegalStateException("Loaded V3 Smart class is not modifiable.");
                inst.redefineClasses(new ClassDefinition(loadedV3, readResource("/payload/SmartBlockHitModeV3.class")));
                out.add("legacyV3Tombstone=PASS class=" + V3);
            } else {
                out.add("legacyV3Tombstone=not-loaded");
            }

            Class<?> v4Class = defineOnce(loader, lookup, V4, "/payload/SmartBlockHitModeV4.class", out);
            defineOnce(loader, lookup, V4_STATE, "/payload/SmartBlockHitModeV4$State.class", out);
            defineOnce(loader, lookup, V4_CLOCK, "/payload/SmartBlockHitModeV4$Clock.class", out);
            out.add("smartImplementation=SMART5-R10-damage-epochs-locked-hurt-clock");

            Class<?> vapeClass = Class.forName("gg.vape.Vape", false, loader);
            Object vape = vapeClass.getField("INSTANCE").get(null);
            if (vape == null) throw new IllegalStateException("Vape.INSTANCE is null.");
            Object modManager = vapeClass.getMethod("getModManager").invoke(vape);
            if (modManager == null) throw new IllegalStateException("Vape ModManager is null; bootstrap is not ready.");
            Class<?> modManagerClass = Class.forName("gg.vape.manager.ModManager", false, loader);
            Object blockHit = modManagerClass.getMethod("getMod", Class.class).invoke(modManager, blockHitClass);
            if (blockHit == null) throw new IllegalStateException("BlockHit module instance was not found.");
            out.add("blockHit=" + blockHit.getClass().getName());

            Class<?> modClass = Class.forName("gg.vape.module.Mod", false, loader);
            Class<?> subModuleClass = Class.forName("gg.vape.module.SubModule", false, loader);
            Class<?> subModuleValueClass = Class.forName("gg.vape.value.SubModuleValue", false, loader);
            Class<?> modeValueClass = Class.forName("gg.vape.value.ModeValue", false, loader);
            Class<?> modeSelectionClass = Class.forName("gg.vape.unmap.ModeSelection", false, loader);
            Class<?> modeOptionClass = Class.forName("gg.vape.unmap.ModeOption", false, loader);
            Class<?> valueClass = Class.forName("gg.vape.value.Value", false, loader);
            Class<?> conditionalValueClass = Class.forName("gg.vape.value.ConditionalValue", false, loader);
            Class<?> dropdownClass = Class.forName("gg.vape.ui.click.component.DropdownSelectComponent", false, loader);
            Class<?> eventBusClass = Class.forName("gg.vape.event.EventBus", false, loader);
            Class<?> eventListenerClass = Class.forName("gg.vape.event.EventListener", false, loader);

            Field modeField = findField(blockHitClass, "mode");
            Object mode = modeField.get(blockHit);
            if (mode == null) throw new IllegalStateException("BlockHit.mode is null.");
            Method getModes = modeValueClass.getMethod("getModes");
            Object modesBefore = getModes.invoke(mode);
            Object selectedBefore = valueClass.getMethod("getValue").invoke(mode);
            boolean smartWasSelected = selectedBefore != null && "Smart".equalsIgnoreCase(String.valueOf(selectedBefore));
            out.add("smartWasSelected=" + smartWasSelected);

            @SuppressWarnings("unchecked")
            List<Object> subs = (List<Object>) modClass.getMethod("getSubModules").invoke(blockHit);
            List<Object> oldSmarts = new ArrayList<Object>();
            for (Object sub : new ArrayList<Object>(subs)) {
                if (sub != null && "Smart".equalsIgnoreCase(String.valueOf(modClass.getMethod("getName").invoke(sub)))) {
                    oldSmarts.add(sub);
                }
            }
            out.add("oldSmartSubModulesFound=" + oldSmarts.size());

            // Preserve user values by field name before removing old instances.
            Map<String,Object> saved = new LinkedHashMap<String,Object>();
            for (Object old : oldSmarts) {
                for (String fieldName : VALUE_FIELDS) {
                    if (saved.containsKey(fieldName)) continue;
                    try {
                        Field f = findField(old.getClass(), fieldName);
                        Object value = f.get(old);
                        if (value != null && valueClass.isInstance(value)) {
                            saved.put(fieldName, valueClass.getMethod("getValue").invoke(value));
                        }
                    } catch (Throwable ignored) {}
                }
            }
            out.add("savedSmartSettings=" + saved.size());

            // Hard listener cleanup: do not rely on Mod.enabled bookkeeping.
            Object eventBus = eventBusClass.getMethod("getInstance").invoke(null);
            Method unregister = eventBusClass.getMethod("unregisterListener", eventListenerClass);
            Method setEnabled = modClass.getMethod("setEnabled", boolean.class, boolean.class);
            int directUnregisters = 0;
            for (Object old : oldSmarts) {
                try { setEnabled.invoke(old, Boolean.FALSE, Boolean.TRUE); } catch (Throwable ignored) {}
                try {
                    Object r = unregister.invoke(eventBus, old);
                    directUnregisters++;
                } catch (Throwable ignored) {}
            }
            out.add("oldSmartDirectUnregisters=" + directUnregisters);

            // Remove old Smart values from BlockHit + ModeValue dependency bookkeeping.
            @SuppressWarnings("unchecked")
            List<Object> blockValues = (List<Object>) modClass.getMethod("getAllValues").invoke(blockHit);
            @SuppressWarnings("unchecked")
            List<Object> modeDeps = (List<Object>) conditionalValueClass.getMethod("getDependentValues").invoke(mode);
            @SuppressWarnings("unchecked")
            Map<Object,Object> activeMap = (Map<Object,Object>) findField(modeValueClass, "activeModesByDependentValue").get(mode);
            Set<Object> oldSet = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            oldSet.addAll(oldSmarts);
            int removedValues = 0;
            for (Iterator<Object> it = blockValues.iterator(); it.hasNext();) {
                Object v = it.next();
                Object owner = null;
                try { owner = valueClass.getMethod("getOwner").invoke(v); } catch (Throwable ignored) {}
                if (owner != null && oldSet.contains(owner)) {
                    it.remove();
                    modeDeps.remove(v);
                    activeMap.remove(v);
                    removedValues++;
                }
            }
            out.add("oldSmartValuesRemoved=" + removedValues);

            // Remove all old Smart submodule objects.
            int removedSubs = 0;
            for (Iterator<Object> it = subs.iterator(); it.hasNext();) {
                Object sub = it.next();
                String name = String.valueOf(modClass.getMethod("getName").invoke(sub));
                if ("Smart".equalsIgnoreCase(name)) { it.remove(); removedSubs++; }
            }
            out.add("oldSmartSubModulesRemoved=" + removedSubs);

            // Remove stale Smart selections from ModeSelection's global registration list.
            try {
                Field sbm = modeSelectionClass.getField("selectionsByMode");
                @SuppressWarnings("unchecked") Map<Object,List<Object>> map = (Map<Object,List<Object>>) sbm.get(null);
                List<Object> selections = map.get(mode);
                int removed = 0;
                if (selections != null) {
                    for (Iterator<Object> it = selections.iterator(); it.hasNext();) {
                        Object s = it.next();
                        if (s != null && "Smart".equalsIgnoreCase(String.valueOf(s))) { it.remove(); removed++; }
                    }
                }
                out.add("oldSmartGlobalSelectionsRemoved=" + removed);
            } catch (Throwable t) {
                out.add("oldSmartGlobalSelectionsRemoved=skipped:" + t.getClass().getSimpleName());
            }

            // Build one new V4 instance and one new selection identity.
            Constructor<?> ctor = v4Class.getConstructor(modClass, String.class);
            Object fresh = ctor.newInstance(blockHit, "Smart");
            Object freshSelection = subModuleClass.getMethod("getSelectionValue").invoke(fresh);
            if (freshSelection == null || !subModuleValueClass.isInstance(freshSelection)) {
                throw new IllegalStateException("V4 Smart selection is missing or invalid.");
            }
            out.add("v4FreshInstanceConstructed=true");

            int restored = 0;
            for (String fieldName : VALUE_FIELDS) {
                if (!saved.containsKey(fieldName)) continue;
                Field f = findField(v4Class, fieldName);
                Object newValue = f.get(fresh);
                valueClass.getMethod("setValue", Object.class).invoke(newValue, saved.get(fieldName));
                restored++;
            }
            out.add("restoredSmartSettings=" + restored);

            modeSelectionClass.getMethod("attachToMode", modeValueClass).invoke(freshSelection, mode);

            // Rebuild the mode array from the four canonical original selections plus V4 Smart.
            Object manual = findSelectionByName(modesBefore, "Manual");
            Object predict = findSelectionByName(modesBefore, "Predict");
            Object auto = findSelectionByName(modesBefore, "Auto");
            Object lag = findSelectionByName(modesBefore, "Lag");
            if (manual == null || predict == null || auto == null || lag == null) {
                throw new IllegalStateException("Could not recover canonical Manual/Predict/Auto/Lag selections.");
            }
            Object canonical = Array.newInstance(modeSelectionClass, 5);
            Array.set(canonical, 0, manual);
            Array.set(canonical, 1, predict);
            Array.set(canonical, 2, freshSelection);
            Array.set(canonical, 3, auto);
            Array.set(canonical, 4, lag);
            setFieldRobust(mode, findField(modeValueClass, "modes"), canonical);
            out.add("modeArrayRebuilt=PASS");

            // Register V4 values under the new selection.
            @SuppressWarnings("unchecked")
            List<Object> freshValues = (List<Object>) v4Class.getMethod("getAllValues").invoke(fresh);
            Method addValue = modClass.getMethod("addValue", Array.newInstance(valueClass, 0).getClass());
            Method addActiveMode = modeValueClass.getMethod("addActiveMode", valueClass, modeOptionClass);
            for (Object value : freshValues) {
                Object one = Array.newInstance(valueClass, 1);
                Array.set(one, 0, value);
                addValue.invoke(blockHit, one);
                addActiveMode.invoke(mode, value, freshSelection);
            }
            out.add("v4ValuesAttached=" + freshValues.size());

            Method registerSub = modClass.getMethod("registerSubModule", Array.newInstance(subModuleClass, 0).getClass());
            Object subArray = Array.newInstance(subModuleClass, 1);
            Array.set(subArray, 0, fresh);
            registerSub.invoke(blockHit, subArray);
            out.add("v4SubModuleRegistered=PASS");

            try {
                modeValueClass.getMethod("setDescription", String.class).invoke(mode,
                    "Manual: Blockhit based on your CPS\n" +
                    "Predict: Predicts when a player can hit you and blocks ahead of time\n" +
                    "Smart: One first-hit attempt per encounter, then local damageability-clock timing; no packet buffering\n" +
                    "Auto: Legacy auto mode from AutoClicker\n" +
                    "Lag: Lags you after blocking to maximize server side block time\n");
            } catch (Throwable ignored) { out.add("descriptionUpdate=skipped"); }

            boolean parentEnabled = ((Boolean) modClass.getMethod("isEnabled").invoke(blockHit)).booleanValue();
            out.add("parentEnabled=" + parentEnabled);
            Path smartLog = smartLogPath();
            if (smartWasSelected) {
                try {
                    Files.createDirectories(smartLog.getParent());
                    Files.write(smartLog, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    out.add("smartLogReset=PASS");
                } catch (Throwable t) {
                    out.add("smartLogReset=FAIL:" + t.getClass().getSimpleName());
                }
                valueClass.getMethod("setValue", Object.class).invoke(mode, freshSelection);
                out.add("modeSelectionReboundToV4=PASS");
            }

            // Normal BlockHit lifecycle is the authority for selected submodule enablement.
            modClass.getMethod("syncSubModuleStates", boolean.class, boolean.class)
                    .invoke(blockHit, Boolean.valueOf(parentEnabled), Boolean.TRUE);
            out.add("subModuleLifecycleSync=PASS");

            Object verifyModes = getModes.invoke(mode);
            out.add("modeCount=" + Array.getLength(verifyModes));
            out.add("modes=" + joinModes(verifyModes));
            if (Array.getLength(verifyModes) != 5 || Array.get(verifyModes, 2) != freshSelection) {
                throw new IllegalStateException("Canonical five-mode array was not retained.");
            }
            refreshBoundDropdown(mode, verifyModes, valueClass, dropdownClass, out);

            @SuppressWarnings("unchecked")
            List<Object> verifySubs = (List<Object>) modClass.getMethod("getSubModules").invoke(blockHit);
            int smartCount = 0;
            Object liveSmart = null;
            for (Object sub : verifySubs) {
                if (sub != null && "Smart".equalsIgnoreCase(String.valueOf(modClass.getMethod("getName").invoke(sub)))) {
                    smartCount++;
                    liveSmart = sub;
                }
            }
            out.add("smartSubModuleCount=" + smartCount);
            out.add("liveSmartClass=" + (liveSmart == null ? "null" : liveSmart.getClass().getName()));
            out.add("liveSmartIsV4=" + (liveSmart == fresh && v4Class.isInstance(liveSmart)));
            if (smartCount != 1 || liveSmart != fresh || !v4Class.isInstance(liveSmart)) {
                throw new IllegalStateException("Exactly one live V4 Smart submodule was not established.");
            }

            Field instanceField = findField(subModuleValueClass, "instance");
            Object selectedInstance = instanceField.get(freshSelection);
            out.add("smartSelectionInstanceIsV4=" + (selectedInstance == fresh));
            if (selectedInstance != fresh) throw new IllegalStateException("Smart selection does not point at V4 instance.");

            boolean freshSelected = ((Boolean) subModuleClass.getMethod("isSelectedSubModule").invoke(fresh)).booleanValue();
            boolean freshEnabled = ((Boolean) modClass.getMethod("isEnabled").invoke(fresh)).booleanValue();
            out.add("freshSelected=" + freshSelected);
            out.add("freshEnabled=" + freshEnabled);

            boolean markerObserved = false;
            if (Files.exists(smartLog)) {
                String text = new String(Files.readAllBytes(smartLog), StandardCharsets.UTF_8);
                markerObserved = text.contains(MARKER);
            }
            out.add("v4OnEnableMarkerObserved=" + markerObserved);
            if (smartWasSelected && parentEnabled) {
                if (!freshSelected || !freshEnabled || !markerObserved) {
                    throw new IllegalStateException("Smart was selected, but V4 lifecycle/marker verification failed.");
                }
            }

            @SuppressWarnings("unchecked")
            List<Object> verifyValues = (List<Object>) v4Class.getMethod("getAllValues").invoke(fresh);
            out.add("smartValues=" + verifyValues.size());
            if (verifyValues.size() != 8) throw new IllegalStateException("V4 Smart does not expose eight values.");

            out.add("RESULT=PASS");
            writeStatus(statusPath, out, null);
        } catch (Throwable t) {
            out.add("RESULT=FAIL");
            writeStatus(statusPath, out, unwrap(t));
        }
    }

    private static Object findSelectionByName(Object array, String name) {
        if (array == null) return null;
        for (int i = 0; i < Array.getLength(array); i++) {
            Object s = Array.get(array, i);
            if (s != null && name.equalsIgnoreCase(String.valueOf(s))) return s;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void refreshBoundDropdown(Object mode, Object modesArray, Class<?> valueClass,
                                             Class<?> dropdownClass, List<String> out) throws Exception {
        Object component = valueClass.getMethod("getBoundComponent").invoke(mode);
        if (component == null) {
            out.add("uiBoundComponent=null");
            return;
        }
        out.add("uiBoundComponent=" + component.getClass().getName());
        if (!dropdownClass.isInstance(component)) {
            out.add("uiRefresh=skipped-non-dropdown");
            return;
        }
        try {
            Field popup = findField(dropdownClass, "popupFrame");
            if (popup.get(component) != null) dropdownClass.getMethod("togglePopup").invoke(component);
        } catch (Throwable ignored) {}
        Field optionsField = findField(dropdownClass, "optionsByGroup");
        Map options = (Map) optionsField.get(component);
        List<Object> fresh = new ArrayList<Object>();
        for (int i = 0; i < Array.getLength(modesArray); i++) fresh.add(Array.get(modesArray, i));
        options.clear();
        options.put(null, fresh);
        out.add("uiOptionCount=" + fresh.size());
        out.add("uiOptions=" + joinList(fresh));
        out.add("uiRefresh=PASS");
    }

    private static Class<?> defineOnce(ClassLoader loader, MethodHandles.Lookup lookup,
                                       String className, String resource, List<String> out) throws Exception {
        try {
            Class<?> c = Class.forName(className, false, loader);
            out.add("reuse=" + className);
            return c;
        } catch (ClassNotFoundException expected) {
            Class<?> c = lookup.defineClass(readResource(resource));
            if (!className.equals(c.getName())) throw new IllegalStateException("Defined " + c.getName() + " instead of " + className);
            out.add("defined=" + className);
            return c;
        }
    }

    private static Class<?> findLoaded(Instrumentation inst, String name) {
        for (Class<?> c : inst.getAllLoadedClasses()) if (name.equals(c.getName())) return c;
        return null;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static void setFieldRobust(Object target, Field field, Object value) throws Exception {
        field.setAccessible(true);
        try {
            field.set(target, value);
            return;
        } catch (IllegalAccessException ignored) {}
        // Java 21 may enforce final-field reflection more strictly; Unsafe is already
        // available to the same target JVM and is used only for object-field rebinding.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        long offset = ((Long)unsafeClass.getMethod("objectFieldOffset", Field.class).invoke(unsafe, field)).longValue();
        unsafeClass.getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, target, offset, value);
    }

    private static byte[] readResource(String path) throws IOException {
        InputStream in = SmartBlockHitHotPatchR10.class.getResourceAsStream(path);
        if (in == null) throw new FileNotFoundException("Missing agent resource " + path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) >= 0;) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static Path smartLogPath() {
        String base = System.getenv("LOCALAPPDATA");
        File dir = base == null || base.isEmpty()
                ? new File(System.getProperty("user.home"), "VapeTrainer")
                : new File(base, "VapeTrainer");
        return new File(dir, "smart-blockhit.log").toPath();
    }

    private static String joinModes(Object array) {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < Array.getLength(array); i++) names.add(String.valueOf(Array.get(array, i)));
        return joinList(names);
    }

    private static String joinList(List<?> list) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) b.append(" / ");
            b.append(String.valueOf(list.get(i)));
        }
        return b.toString();
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof InvocationTargetException || t instanceof ExceptionInInitializerError) && t.getCause() != null) t = t.getCause();
        return t;
    }

    private static void writeStatus(String path, List<String> lines, Throwable error) {
        PrintWriter out = null;
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
            for (String line : lines) out.println(line);
            if (error != null) {
                out.println("error=" + error);
                error.printStackTrace(out);
            }
        } catch (Throwable ignored) {
        } finally {
            if (out != null) out.close();
        }
    }
}
