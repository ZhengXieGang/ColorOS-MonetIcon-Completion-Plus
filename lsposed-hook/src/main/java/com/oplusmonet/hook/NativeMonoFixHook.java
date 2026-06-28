package com.oplusmonet.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class NativeMonoFixHook extends XposedModule {
    private static final String TAG = "OplusNativeMonoFix";
    private static final int DEFAULT_NATIVE_MONO_CONTENT_PX = 90;
    private static final int MIN_NATIVE_MONO_CONTENT_PX = 90;
    private static final int MAX_NATIVE_MONO_CONTENT_PX = 110;
    private static final int DEFAULT_MORPH_1X2_CONTENT_PX = 160;
    private static final int DEFAULT_MORPH_2X1_CONTENT_PX = 160;
    private static final int DEFAULT_MORPH_2X2_CONTENT_PX = 320;
    private static final int NORMAL_CONTENT_REFERENCE_FRAME_PX = 160;
    private static final int MIN_VISIBLE_CONTENT_ALPHA = 16;
    private static final float VISIBLE_CONTENT_ALPHA_RATIO = 0.20f;
    private static final long CONFIG_CACHE_MS = 3000L;
    private static final String SETTING_NORMAL_PX = "oplus_native_mono_normal_px";
    private static final String SETTING_MORPH_1X2_PX = "oplus_native_mono_morph_1x2_px";
    private static final String SETTING_MORPH_2X1_PX = "oplus_native_mono_morph_2x1_px";
    private static final String SETTING_MORPH_2X2_PX = "oplus_native_mono_morph_2x2_px";
    private static final String[] CONFIG_PATHS = {
            "/data/oplus/uxicons/.native_mono_fix.conf",
            "/my_product/media/theme/uxicons/hdpi/.native_mono_fix.conf",
            "/data/adb/modules/ThemedIconCompletion/webroot/native_mono_fix.conf"
    };
    private static final String[] UXICON_ROOTS = {
            "/data/oplus/uxicons",
            "/my_product/media/theme/uxicons/hdpi"
    };
    private static final String[] TASK_ICON_CACHE_CLASSES = {
            "com.android.quickstep.TaskIconCache",
            "com.android.quickstep.OplusTaskIconCacheImpl"
    };
    private static final HookSpec[] HOOK_SPECS = {
            new HookSpec(
                    "launcher-uxicon",
                    "com.oplus.uxicon.ui.morphicon.MonoChromeIconLoaderFactory",
                    "assembleDrawable",
                    "com.oplus.uxicon.ui.morphicon.IconFormat",
                    "com.oplus.uxicon.helper.IconConfig",
                    "com.oplus.uxicon.ui.util.UxIconLoaderUtil",
                    "handleIconThemeFgDrawable",
                    "com.oplus.uxicon.ui.util.UxIconLoaderHelper",
                    "getIconThemeDrawableWithComponent",
                    "loadUxIconByPath"),
            new HookSpec(
                    "uxdesign-uxicon",
                    "com.oplus.uxicon.ui.morphicon.l",
                    "b",
                    "com.oplus.uxicon.ui.morphicon.f",
                    "com.oplus.uxicon.helper.IconConfig",
                    "q8.n",
                    "C0",
                    "q8.l",
                    "d",
                    "W0")
    };
    private String processName = "";
    private boolean nativeMonochromeHookInstalled;
    private boolean launcherAppIconHookInstalled;
    private boolean launcherItemInfoHookInstalled;
    private boolean launcherBitmapInfoHookInstalled;
    private boolean launcherMorphCacheHookInstalled;
    private boolean launcherLocalSpecialHookInstalled;
    private boolean launcherTaskIconCacheHookInstalled;
    private boolean frameworkDarkFilterHookInstalled;
    private final Set<String> installedSpecs = ConcurrentHashMap.newKeySet();
    private final Set<Drawable> nativeMonoFallbackDrawables =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<Drawable> nativeMonoRecentTaskDrawables =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<Object, ComponentName> bitmapInfoComponents =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Integer> bitmapInfoNativeMonoSizes =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Integer> taskCacheEntryNativeMonoIconSizes =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Integer> taskCacheEntryNativeMonoSplitIconSizes =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ConcurrentHashMap<String, Boolean> nativeMonoComponentCache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> existingUxIconPackageCache =
            new ConcurrentHashMap<>();
    private final Set<String> loggedNativeMonoComponents = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedDisabledFilterComponents = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedNativeAppIconComponents = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedSkippedUxIconComponents = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedSuppressedUxMonoComponents = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedSuppressedPathComponents = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedForcedThemedComponents = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedLocalSpecialComponents = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedMatchedFrameComponents = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedRecentTaskIconComponents = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Boolean> inMonoChromeAssembly = new ThreadLocal<>();
    private final ThreadLocal<ComponentName> currentMonoChromeComponent = new ThreadLocal<>();
    private final ThreadLocal<Context> currentMonoChromeContext = new ThreadLocal<>();
    private final ThreadLocal<ComponentName> currentAppIconComponent = new ThreadLocal<>();
    private final ThreadLocal<Context> currentMorphIconContext = new ThreadLocal<>();
    private final ThreadLocal<Boolean> forcingBitmapInfoNewIcon = new ThreadLocal<>();
    private volatile NativeMonoConfig cachedNativeMonoConfig = NativeMonoConfig.defaults();
    private volatile long lastNativeMonoConfigReadMs;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        ClassLoader loader = param.getClassLoader();
        hookFrameworkDarkFilter();
        boolean installedAny = hookLauncherCommon(loader, param.getPackageName());
        for (HookSpec spec : HOOK_SPECS) {
            if (!installedSpecs.contains(spec.name) && spec.isAvailable(loader)) {
                installedSpecs.add(spec.name);
                log("loading " + spec.name + " in package=" + param.getPackageName()
                        + " process=" + processName);
                hookMonoChromeAssembly(loader, spec);
                hookUxMonoResourceBypass(loader, spec);
                hookMorphMono(loader, spec);
                if ("launcher-uxicon".equals(spec.name)) {
                    hookLauncherLocalSpecialDrawable(loader);
                }
                installedAny = true;
            }
        }

        if (installedAny) {
            hookNativeMonochromeResult();
        }
    }

    private boolean hookLauncherCommon(ClassLoader loader, String packageName) {
        if (!hasClass(loader, "com.android.launcher3.icons.BitmapInfo")
                && !hasClass(loader, "com.android.quickstep.TaskIconCache")) {
            return false;
        }

        hookLauncherAppIconBitmap(loader);
        hookLauncherItemInfoNewIcon(loader);
        hookLauncherBitmapInfoNewIcon(loader);
        hookLauncherMorphCache(loader);
        hookTaskIconCache(loader);
        boolean hookedAny = launcherAppIconHookInstalled
                || launcherItemInfoHookInstalled
                || launcherBitmapInfoHookInstalled
                || launcherMorphCacheHookInstalled
                || launcherTaskIconCacheHookInstalled;
        if (hookedAny) {
            log("installed generic Launcher3/Quickstep hooks in package=" + packageName
                    + " process=" + processName);
        }
        return hookedAny;
    }

    private void hookMonoChromeAssembly(ClassLoader loader, HookSpec spec) {
        try {
            Class<?> factoryClass = findClass(loader, spec.factoryClassName);
            Class<?> iconFormatClass = findClass(loader, spec.iconFormatClassName);
            Class<?> iconConfigClass = findClass(loader, spec.iconConfigClassName);
            Class<?> utilClass = findClass(loader, spec.utilClassName);
            Method method = factoryClass.getDeclaredMethod(
                    spec.factoryMethodName, Context.class, ComponentName.class,
                    iconFormatClass, iconConfigClass, utilClass);
            method.setAccessible(true);

            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Boolean previousFlag = inMonoChromeAssembly.get();
                        ComponentName previousComponent = currentMonoChromeComponent.get();
                        Context previousContext = currentMonoChromeContext.get();
                        inMonoChromeAssembly.set(Boolean.TRUE);
                        Object context = chain.getArg(0);
                        if (context instanceof Context) {
                            currentMonoChromeContext.set((Context) context);
                        } else {
                            currentMonoChromeContext.remove();
                        }
                        Object component = chain.getArg(1);
                        if (component instanceof ComponentName) {
                            currentMonoChromeComponent.set((ComponentName) component);
                        } else {
                            currentMonoChromeComponent.remove();
                        }
                        try {
                            return chain.proceed();
                        } finally {
                            restoreThreadLocal(inMonoChromeAssembly, previousFlag);
                            restoreThreadLocal(currentMonoChromeComponent, previousComponent);
                            restoreThreadLocal(currentMonoChromeContext, previousContext);
                        }
                    });
            log("hooked " + spec.factoryClassName + "#" + spec.factoryMethodName);
        } catch (Throwable t) {
            log("MonoChrome assembly hook skipped for " + spec.name + ": " + t);
        }
    }

    private void hookLauncherAppIconBitmap(ClassLoader loader) {
        if (launcherAppIconHookInstalled) {
            return;
        }
        try {
            Class<?> launcherIconsClass = findClass(loader, "com.android.launcher3.icons.LauncherIcons");
            Method method = launcherIconsClass.getDeclaredMethod(
                    "createAppIconBitmap", Context.class, LauncherActivityInfo.class, int.class);
            method.setAccessible(true);

            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object activityInfoArg = chain.getArg(1);
                        Object contextArg = chain.getArg(0);
                        if (!(activityInfoArg instanceof LauncherActivityInfo)
                                || !(contextArg instanceof Context)) {
                            return chain.proceed();
                        }

                        Context context = (Context) contextArg;
                        LauncherActivityInfo activityInfo = (LauncherActivityInfo) activityInfoArg;
                        ComponentName component = activityInfo.getComponentName();
                        ComponentName previousComponent = currentAppIconComponent.get();
                        currentAppIconComponent.set(component);
                        try {
                            Object result = chain.proceed();
                            if (!isThemedIconEnabled(loader, context)
                                    || !shouldUseNativeMono(context, component)
                                    || result == null) {
                                return result;
                            }

                            rememberBitmapInfoComponent(result, component);
                            boolean attached = ensureNativeMonoOnBitmapInfo(
                                    loader, context, component, result, chain.getThisObject(),
                                    getNativeMonoConfig(context).normalPx);
                            if (attached || getMonoBitmap(result) != null) {
                                String key = component.flattenToShortString();
                                if (loggedNativeAppIconComponents.add(key)) {
                                    log("attached native Android mono to launcher BitmapInfo: " + key);
                                }
                            }
                            return result;
                        } finally {
                            restoreThreadLocal(currentAppIconComponent, previousComponent);
                        }
                    });
            launcherAppIconHookInstalled = true;
            log("hooked LauncherIcons.createAppIconBitmap");
        } catch (Throwable t) {
            log("Launcher app icon hook skipped: " + t);
        }
    }

    private void hookLauncherItemInfoNewIcon(ClassLoader loader) {
        if (launcherItemInfoHookInstalled) {
            return;
        }
        try {
            Class<?> itemInfoClass = findClass(loader,
                    "com.android.launcher3.model.data.ItemInfoWithIcon");
            Method method = itemInfoClass.getDeclaredMethod("newIcon", Context.class, int.class);
            method.setAccessible(true);

            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object contextArg = chain.getArg(0);
                        Object flagsArg = chain.getArg(1);
                        if (!(contextArg instanceof Context) || !(flagsArg instanceof Integer)) {
                            return result;
                        }

                        Context context = (Context) contextArg;
                        int flags = ((Integer) flagsArg).intValue();
                        if (!isThemedIconEnabled(loader, context)) {
                            return result;
                        }

                        Object itemInfo = chain.getThisObject();
                        ComponentName component = getTargetComponent(itemInfo);
                        if (!shouldUseNativeMono(context, component)) {
                            return result;
                        }

                        Object bitmapInfo = getFieldValue(itemInfo, "bitmap");
                        if (bitmapInfo == null) {
                            return result;
                        }
                        rememberBitmapInfoComponent(bitmapInfo, component);
                        Object iconFactory = obtainLauncherIcons(loader, context);
                        try {
                            ensureNativeMonoOnBitmapInfo(
                                    loader, context, component, bitmapInfo, iconFactory,
                                    getNativeMonoConfig(context).normalPx);
                        } finally {
                            recycleIconFactory(iconFactory);
                        }
                        if (getMonoBitmap(bitmapInfo) == null) {
                            return result;
                        }

                        Object themedIcon = invokeBitmapInfoNewIcon(bitmapInfo, context, flags | 1);
                        if (themedIcon != null) {
                            String key = component.flattenToShortString();
                            if (loggedForcedThemedComponents.add(key)) {
                                log("forced native themed icon drawable for: " + key);
                            }
                            return themedIcon;
                        }
                        return result;
                    });
            launcherItemInfoHookInstalled = true;
            log("hooked ItemInfoWithIcon.newIcon");
        } catch (Throwable t) {
            log("ItemInfoWithIcon hook skipped: " + t);
        }
    }

    private void hookLauncherBitmapInfoNewIcon(ClassLoader loader) {
        if (launcherBitmapInfoHookInstalled) {
            return;
        }
        try {
            Class<?> bitmapInfoClass = findClass(loader, "com.android.launcher3.icons.BitmapInfo");
            Method method = bitmapInfoClass.getDeclaredMethod("newIcon", Context.class, int.class);
            method.setAccessible(true);

            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (Boolean.TRUE.equals(forcingBitmapInfoNewIcon.get())) {
                            return chain.proceed();
                        }
                        Object result = chain.proceed();
                        Object contextArg = chain.getArg(0);
                        Object flagsArg = chain.getArg(1);
                        if (!(contextArg instanceof Context) || !(flagsArg instanceof Integer)) {
                            return result;
                        }
                        Context context = (Context) contextArg;
                        if (!isThemedIconEnabled(loader, context)) {
                            return result;
                        }
                        Object bitmapInfo = chain.getThisObject();
                        ComponentName component = getRememberedBitmapInfoComponent(bitmapInfo);
                        if (!shouldUseNativeMono(context, component)) {
                            return result;
                        }
                        Object iconFactory = obtainLauncherIcons(loader, context);
                        try {
                            ensureNativeMonoOnBitmapInfo(
                                    loader, context, component, bitmapInfo, iconFactory,
                                    getNativeMonoConfig(context).normalPx);
                        } finally {
                            recycleIconFactory(iconFactory);
                        }
                        if (getMonoBitmap(bitmapInfo) == null) {
                            return result;
                        }
                        return invokeBitmapInfoNewIcon(
                                bitmapInfo, context, ((Integer) flagsArg).intValue() | 1);
                    });
            launcherBitmapInfoHookInstalled = true;
            log("hooked BitmapInfo.newIcon");
        } catch (Throwable t) {
            log("BitmapInfo hook skipped: " + t);
        }
    }

    private void hookLauncherMorphCache(ClassLoader loader) {
        if (launcherMorphCacheHookInstalled) {
            return;
        }
        boolean hookedAny = false;
        try {
            Class<?> iconUtilsClass = findClass(loader, "com.android.common.util.IconUtils");
            Class<?> requestClass = findClass(loader, "com.android.common.util.IconUtils$MorphIconLoadRequest");
            Class<?> iconCacheClass = findClass(loader, "com.android.launcher3.icons.IconCache");
            Class<?> deviceProfileClass = findClass(loader, "com.android.launcher3.DeviceProfile");
            Method loadMorphIcon = iconUtilsClass.getDeclaredMethod(
                    "loadMorphIcon", requestClass, iconCacheClass,
                    deviceProfileClass, Context.class, java.util.function.Consumer.class);
            loadMorphIcon.setAccessible(true);
            hook(loadMorphIcon)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Context previousContext = currentMorphIconContext.get();
                        Object contextArg = chain.getArg(3);
                        if (contextArg instanceof Context) {
                            currentMorphIconContext.set((Context) contextArg);
                        } else {
                            currentMorphIconContext.remove();
                        }
                        try {
                            Object result = chain.proceed();
                            attachNativeMonoToMorphRequest(loader, currentMorphIconContext.get(), result);
                            return result;
                        } finally {
                            restoreThreadLocal(currentMorphIconContext, previousContext);
                        }
                    });
            hookedAny = true;
            log("hooked IconUtils.loadMorphIcon");
        } catch (Throwable t) {
            log("IconUtils.loadMorphIcon hook skipped: " + t);
        }

        try {
            Class<?> itemInfoClass = findClass(loader,
                    "com.android.launcher3.model.data.ItemInfoWithIcon");
            Class<?> bitmapInfoClass = findClass(loader, "com.android.launcher3.icons.BitmapInfo");
            Method updateMorphIconCache = itemInfoClass.getDeclaredMethod(
                    "updateMorphIconCache", int.class, int.class, bitmapInfoClass);
            updateMorphIconCache.setAccessible(true);
            hook(updateMorphIconCache)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object itemInfo = chain.getThisObject();
                        Object bitmapInfo = chain.getArg(2);
                        ComponentName component = getTargetComponent(itemInfo);
                        if (bitmapInfo != null && component != null) {
                            rememberBitmapInfoComponent(bitmapInfo, component);
                            Context context = currentMorphIconContext.get();
                            if (shouldUseNativeMono(context, component)) {
                                int maxContentPx = getMorphNativeMonoMaxPx(
                                        context, asInt(chain.getArg(0), 1),
                                        asInt(chain.getArg(1), 1));
                                Object iconFactory = obtainLauncherIcons(loader, context);
                                try {
                                    ensureNativeMonoOnBitmapInfo(
                                            loader, context, component, bitmapInfo, iconFactory,
                                            maxContentPx);
                                } finally {
                                    recycleIconFactory(iconFactory);
                                }
                            }
                        }
                        return chain.proceed();
                    });
            hookedAny = true;
            log("hooked ItemInfoWithIcon.updateMorphIconCache");
        } catch (Throwable t) {
            log("updateMorphIconCache hook skipped: " + t);
        }

        try {
            Class<?> itemInfoClass = findClass(loader,
                    "com.android.launcher3.model.data.ItemInfoWithIcon");
            Method loadFromMorphIconCache = itemInfoClass.getDeclaredMethod(
                    "loadFromMorphIconCache", int.class, int.class);
            loadFromMorphIconCache.setAccessible(true);
            hook(loadFromMorphIconCache)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        ComponentName component = getTargetComponent(chain.getThisObject());
                        if (result != null && component != null) {
                            rememberBitmapInfoComponent(result, component);
                        }
                        return result;
                    });
            hookedAny = true;
            log("hooked ItemInfoWithIcon.loadFromMorphIconCache");
        } catch (Throwable t) {
            log("loadFromMorphIconCache hook skipped: " + t);
        }

        launcherMorphCacheHookInstalled = hookedAny;
    }

    private void hookLauncherLocalSpecialDrawable(ClassLoader loader) {
        if (launcherLocalSpecialHookInstalled) {
            return;
        }
        try {
            Class<?> iconConfigClass = findClass(loader, "com.oplus.uxicon.helper.IconConfig");
            boolean helperHooked = hookLocalSpecialMethod(loader,
                    "com.oplus.uxicon.ui.util.UxIconLoaderHelper",
                    "getLocalSpecialDrawable", iconConfigClass);
            boolean utilHooked = hookLocalSpecialMethod(loader,
                    "com.oplus.uxicon.ui.util.UxIconLoaderUtil",
                    "getLocalSpecialDrawable", iconConfigClass);
            launcherLocalSpecialHookInstalled = helperHooked || utilHooked;
        } catch (Throwable t) {
            log("Launcher local special hooks skipped: " + t);
        }
    }

    private void hookTaskIconCache(ClassLoader loader) {
        if (launcherTaskIconCacheHookInstalled) {
            return;
        }
        boolean hookedAny = false;
        for (String className : TASK_ICON_CACHE_CLASSES) {
            hookedAny |= hookTaskIconCacheClass(loader, className);
            hookedAny |= hookSplitTaskIconCache(loader, className);
        }
        launcherTaskIconCacheHookInstalled = hookedAny;
    }

    private boolean hookTaskIconCacheClass(ClassLoader loader, String className) {
        try {
            Class<?> cacheClass = findClass(loader, className);
            boolean hookedAny = false;
            for (Method method : cacheClass.getDeclaredMethods()) {
                if (!"getCacheEntry".equals(method.getName())
                        || method.getParameterTypes().length != 1) {
                    continue;
                }
                method.setAccessible(true);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            fixTaskCacheEntryDrawable(loader, chain.getThisObject(),
                                    chain.getArg(0), result, "icon");
                            return result;
                        });
                hookedAny = true;
                log("hooked " + className + "#" + method.getName());
            }
            return hookedAny;
        } catch (Throwable t) {
            log("Task icon cache hook skipped for " + className + ": " + t);
            return false;
        }
    }

    private boolean hookSplitTaskIconCache(ClassLoader loader, String className) {
        try {
            Class<?> cacheClass = findClass(loader, className);
            boolean hookedAny = false;
            for (Method method : cacheClass.getDeclaredMethods()) {
                if (!"getSplitScreenCacheEntry".equals(method.getName())
                        || method.getParameterTypes().length != 1) {
                    continue;
                }
                method.setAccessible(true);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (result instanceof java.util.List) {
                                java.util.List<?> entries = (java.util.List<?>) result;
                                Object groupTask = chain.getArg(0);
                                if (entries.size() > 0) {
                                    fixTaskCacheEntryDrawable(loader, chain.getThisObject(),
                                            getFieldValue(groupTask, "task1"), entries.get(0),
                                            "splitIcon");
                                }
                                if (entries.size() > 1) {
                                    fixTaskCacheEntryDrawable(loader, chain.getThisObject(),
                                            getFieldValue(groupTask, "task2"), entries.get(1),
                                            "splitIcon");
                                }
                            }
                            return result;
                        });
                hookedAny = true;
                log("hooked " + className + "#" + method.getName());
            }
            return hookedAny;
        } catch (Throwable t) {
            log("Split task icon cache hook skipped for " + className + ": " + t);
            return false;
        }
    }

    private void fixTaskCacheEntryDrawable(ClassLoader loader, Object taskIconCache,
            Object task, Object cacheEntry, String iconFieldName) {
        if (taskIconCache == null || task == null || cacheEntry == null) {
            return;
        }
        Object contextValue = getFieldValue(taskIconCache, "mContext");
        if (!(contextValue instanceof Context)) {
            return;
        }
        Context context = (Context) contextValue;
        if (!isThemedIconEnabled(loader, context)) {
            return;
        }
        ComponentName component = getTaskComponent(context, task);
        if (!shouldUseNativeMono(context, component)) {
            return;
        }
        Object currentDrawable = getFieldValue(cacheEntry, iconFieldName);
        if (!(currentDrawable instanceof Drawable)) {
            return;
        }
        int targetMaxContentPx = getNativeMonoConfig(context).normalPx;
        Map<Object, Integer> appliedSizes = "splitIcon".equals(iconFieldName)
                ? taskCacheEntryNativeMonoSplitIconSizes : taskCacheEntryNativeMonoIconSizes;
        Integer appliedSize = appliedSizes.get(cacheEntry);
        if (appliedSize != null && appliedSize.intValue() == targetMaxContentPx
                && isRecentNativeMonoDrawable((Drawable) currentDrawable)) {
            return;
        }

        Object iconFactory = getTaskIconFactory(taskIconCache);
        boolean recycleFactory = false;
        if (iconFactory == null) {
            iconFactory = obtainLauncherIcons(loader, context);
            recycleFactory = true;
        }
        if (iconFactory == null) {
            return;
        }
        try {
            Drawable themedDrawable = buildNativeMonoRecentTaskDrawable(
                    loader, context, component, (Drawable) currentDrawable,
                    iconFactory, targetMaxContentPx);
            if (themedDrawable == null) {
                return;
            }
            if (setFieldValue(cacheEntry, iconFieldName, themedDrawable)) {
                nativeMonoRecentTaskDrawables.add(themedDrawable);
                appliedSizes.put(cacheEntry, Integer.valueOf(targetMaxContentPx));
                String key = component.flattenToShortString() + "#" + iconFieldName;
                if (loggedRecentTaskIconComponents.add(key)) {
                    log("fixed recent task native themed icon: " + key);
                }
            }
        } finally {
            if (recycleFactory) {
                recycleIconFactory(iconFactory);
            }
        }
    }

    private Drawable buildNativeMonoRecentTaskDrawable(ClassLoader loader, Context context,
            ComponentName component, Drawable frameDrawable, Object iconFactory,
            int targetMaxContentPx) {
        Bitmap frameBitmap = drawableToBitmap(frameDrawable, context);
        if (frameBitmap == null) {
            return null;
        }
        Object bitmapInfo = createBitmapInfo(loader, frameBitmap, 0);
        if (bitmapInfo == null) {
            return null;
        }
        ensureNativeMonoOnBitmapInfo(
                loader, context, component, bitmapInfo, iconFactory, targetMaxContentPx);
        if (getMonoBitmap(bitmapInfo) == null) {
            return null;
        }
        Object themedDrawable = invokeBitmapInfoNewIcon(bitmapInfo, context, 1);
        return themedDrawable instanceof Drawable ? (Drawable) themedDrawable : null;
    }

    private Object createBitmapInfo(ClassLoader loader, Bitmap bitmap, int color) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        try {
            Class<?> bitmapInfoClass = findClass(loader, "com.android.launcher3.icons.BitmapInfo");
            Method of = bitmapInfoClass.getDeclaredMethod("of", Bitmap.class, int.class);
            of.setAccessible(true);
            return of.invoke(null, bitmap, Integer.valueOf(color));
        } catch (Throwable t) {
            log("BitmapInfo.of failed for recent task icon: " + t);
            return null;
        }
    }

    private Bitmap drawableToBitmap(Drawable drawable, Context context) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof BitmapDrawable) {
            try {
                Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                if (bitmap != null && !bitmap.isRecycled()
                        && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
                    return bitmap;
                }
            } catch (Throwable ignored) {
            }
        }

        Rect bounds = drawable.getBounds();
        int width = bounds.width();
        int height = bounds.height();
        if (width <= 0 || height <= 0) {
            width = drawable.getIntrinsicWidth();
            height = drawable.getIntrinsicHeight();
        }
        if ((width <= 0 || height <= 0) && context != null) {
            int fallbackSize = Math.max(1, Math.round(
                    48.0f * context.getResources().getDisplayMetrics().density));
            width = fallbackSize;
            height = fallbackSize;
        }
        if (width <= 0 || height <= 0) {
            return null;
        }

        Bitmap output;
        try {
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable ignored) {
            return null;
        }
        Canvas canvas = new Canvas(output);
        Rect oldBounds = new Rect(bounds);
        try {
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
        } catch (Throwable ignored) {
            try {
                output.recycle();
            } catch (Throwable ignoredRecycle) {
            }
            output = null;
        } finally {
            try {
                drawable.setBounds(oldBounds);
            } catch (Throwable ignoredRestore) {
            }
        }
        return output;
    }

    private Object getTaskIconFactory(Object taskIconCache) {
        if (taskIconCache == null) {
            return null;
        }
        try {
            Method method = findMethodInHierarchy(taskIconCache.getClass(), "getIconFactory");
            method.setAccessible(true);
            return method.invoke(taskIconCache);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ComponentName getTaskComponent(Context context, Object task) {
        if (task == null) {
            return null;
        }
        Object key = getFieldValue(task, "key");
        ComponentName component = invokeComponentMethod(key, "getComponent");
        if (component != null) {
            return component;
        }
        component = invokeComponentMethod(task, "getTopComponent");
        if (component != null) {
            return component;
        }
        Object topActivity = getFieldValue(task, "topActivity");
        if (topActivity instanceof ComponentName) {
            return (ComponentName) topActivity;
        }
        Object sourceComponent = getFieldValue(key, "sourceComponent");
        if (sourceComponent instanceof ComponentName) {
            return (ComponentName) sourceComponent;
        }
        Object baseIntent = getFieldValue(key, "baseIntent");
        component = invokeComponentMethod(baseIntent, "getComponent");
        if (component != null) {
            return component;
        }
        String packageName = getTaskPackageName(task, key);
        if (context == null || packageName == null || packageName.length() == 0) {
            return null;
        }
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            return intent != null ? intent.getComponent() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ComponentName invokeComponentMethod(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = findMethodInHierarchy(target.getClass(), methodName);
            method.setAccessible(true);
            Object result = method.invoke(target);
            return result instanceof ComponentName ? (ComponentName) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String getTaskPackageName(Object task, Object key) {
        Object result = invokeNoArg(task, "getPackageName");
        if (result instanceof String) {
            return (String) result;
        }
        result = invokeNoArg(key, "getPackageName");
        if (result instanceof String) {
            return (String) result;
        }
        Object baseIntent = getFieldValue(key, "baseIntent");
        result = invokeNoArg(baseIntent, "getPackage");
        return result instanceof String ? (String) result : null;
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = findMethodInHierarchy(target.getClass(), methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean hookLocalSpecialMethod(ClassLoader loader, String className, String methodName,
            Class<?> iconConfigClass) {
        try {
            Class<?> cls = findClass(loader, className);
            Method method = cls.getDeclaredMethod(
                    methodName, Context.class, iconConfigClass, Drawable.class);
            method.setAccessible(true);

            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object contextArg = chain.getArg(0);
                        Object drawableArg = chain.getArg(2);
                        ComponentName component = currentMonoChromeComponent.get();
                        if (isInMonoChromeAssembly()
                                && contextArg instanceof Context
                                && drawableArg instanceof Drawable
                                && shouldUseNativeMono((Context) contextArg, component)) {
                            String key = component.flattenToShortString();
                            if (loggedLocalSpecialComponents.add(key)) {
                                log("bypassed local special icon wrapper for native mono: " + key);
                            }
                            return drawableArg;
                        }
                        return chain.proceed();
                    });
            log("hooked " + className + "#" + methodName);
            return true;
        } catch (Throwable t) {
            log("Local special hook skipped for " + className + ": " + t);
            return false;
        }
    }

    private Drawable loadNativeMonoAdaptiveDrawable(Context context, Object iconFactory,
            LauncherActivityInfo activityInfo) {
        ComponentName component = activityInfo.getComponentName();
        Drawable drawable = loadNativeMonoAdaptiveDrawable(context, component);
        if (!hasNativeMonochrome(drawable)) {
            try {
                drawable = activityInfo.getIcon(getFillResIconDpi(iconFactory, context));
            } catch (Throwable ignored) {
                drawable = null;
            }
        }
        if (!hasNativeMonochrome(drawable)) {
            return null;
        }
        try {
            return drawable.mutate();
        } catch (Throwable ignored) {
            return drawable;
        }
    }

    private Drawable loadNativeMonoAdaptiveDrawable(Context context, ComponentName component) {
        if (context == null || component == null) {
            return null;
        }
        Drawable drawable = loadRawActivityIconFromApk(context, component);
        if (hasNativeMonochrome(drawable)) {
            try {
                return drawable.mutate();
            } catch (Throwable ignored) {
                return drawable;
            }
        }
        try {
            PackageManager packageManager = context.getPackageManager();
            if (packageManager != null) {
                drawable = packageManager.getActivityIcon(component);
            }
        } catch (Throwable ignored) {
            drawable = null;
        }
        if (!hasNativeMonochrome(drawable)) {
            return null;
        }
        try {
            return drawable.mutate();
        } catch (Throwable ignored) {
            return drawable;
        }
    }

    private Drawable loadRawActivityIconFromApk(Context context, ComponentName component) {
        try {
            PackageManager packageManager = context.getPackageManager();
            if (packageManager == null) {
                return null;
            }
            ActivityInfo activityInfo = packageManager.getActivityInfo(component, 128);
            int iconRes = activityInfo.getIconResource();
            if (iconRes == 0 && activityInfo.applicationInfo != null) {
                iconRes = activityInfo.applicationInfo.icon;
            }
            if (iconRes == 0 || activityInfo.applicationInfo == null) {
                return null;
            }
            Resources resources = packageManager.getResourcesForApplication(
                    activityInfo.applicationInfo);
            int density = context.getResources().getDisplayMetrics().densityDpi;
            Drawable drawable = resources.getDrawableForDensity(iconRes, density, null);
            if (drawable != null) {
                return drawable;
            }
            return resources.getDrawable(iconRes, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean hasNativeMonochrome(Drawable drawable) {
        if (!(drawable instanceof AdaptiveIconDrawable)) {
            return false;
        }
        try {
            return ((AdaptiveIconDrawable) drawable).getMonochrome() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean componentHasNativeMonochrome(Context context, ComponentName component) {
        if (context == null || component == null) {
            return false;
        }
        String key = component.flattenToShortString();
        Boolean cached = nativeMonoComponentCache.get(key);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean hasNativeMono = loadNativeMonoAdaptiveDrawable(context, component) != null;
        nativeMonoComponentCache.put(key, Boolean.valueOf(hasNativeMono));
        if (hasNativeMono && loggedNativeMonoComponents.add(key)) {
            log("native APK monochrome available: " + key);
        }
        return hasNativeMono;
    }

    private boolean shouldUseNativeMono(Context context, ComponentName component) {
        if (context == null || component == null) {
            return false;
        }
        if (!componentHasNativeMonochrome(context, component)) {
            return false;
        }
        if (hasExistingUxIcon(component)) {
            String key = component.flattenToShortString();
            if (loggedSkippedUxIconComponents.add(key)) {
                log("kept existing uxicon resource, skip native mono hook: " + key);
            }
            return false;
        }
        return true;
    }

    private boolean hasExistingUxIcon(ComponentName component) {
        if (component == null || component.getPackageName() == null) {
            return false;
        }
        String packageName = component.getPackageName();
        Boolean cached = existingUxIconPackageCache.get(packageName);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean exists = hasUxIconDirWithFiles(packageName);
        if (!exists) {
            String lower = packageName.toLowerCase(Locale.US);
            if (!lower.equals(packageName)) {
                exists = hasUxIconDirWithFiles(lower);
            }
        }
        existingUxIconPackageCache.put(packageName, Boolean.valueOf(exists));
        return exists;
    }

    private boolean hasUxIconDirWithFiles(String packageName) {
        for (String root : UXICON_ROOTS) {
            File dir = new File(root, packageName);
            if (!dir.isDirectory()) {
                continue;
            }
            String[] children = dir.list();
            if (children != null && children.length > 0) {
                return true;
            }
        }
        return false;
    }

    private int getFillResIconDpi(Object iconFactory, Context context) {
        try {
            Field field = findFieldInHierarchy(iconFactory.getClass(), "mFillResIconDpi");
            field.setAccessible(true);
            return field.getInt(iconFactory);
        } catch (Throwable ignored) {
            return context.getResources().getDisplayMetrics().densityDpi;
        }
    }

    private Object buildNativeMonoBitmapInfo(ClassLoader loader, Object iconFactory,
            Drawable drawable, UserHandle user) {
        try {
            Class<?> iconOptionsClass =
                    findClass(loader, "com.android.launcher3.icons.BaseIconFactory$IconOptions");
            Object iconOptions = iconOptionsClass.getDeclaredConstructor().newInstance();
            Method setUser = iconOptionsClass.getDeclaredMethod("setUser", UserHandle.class);
            setUser.setAccessible(true);
            setUser.invoke(iconOptions, user);

            Method createBadgedIconBitmap = findMethodInHierarchy(iconFactory.getClass(),
                    "createBadgedIconBitmap", Drawable.class, iconOptionsClass);
            createBadgedIconBitmap.setAccessible(true);
            return createBadgedIconBitmap.invoke(iconFactory, drawable, iconOptions);
        } catch (Throwable t) {
            log("Native mono BitmapInfo build failed: " + t);
            return null;
        }
    }

    private boolean isThemedIconEnabled(ClassLoader loader, Context context) {
        try {
            Class<?> themesClass = findClass(loader, "com.android.launcher3.util.Themes");
            Method method = themesClass.getDeclaredMethod("isThemedIconEnabled", Context.class);
            method.setAccessible(true);
            Object result = method.invoke(null, context);
            if (Boolean.TRUE.equals(result)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> configClass = findClass(loader, "com.android.launcher.theme.LauncherIconConfig");
            Method getInstance = configClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object instance = getInstance.invoke(null);
            Method isThemed = findMethodInHierarchy(instance.getClass(), "isThemedIconEnabled");
            isThemed.setAccessible(true);
            Object result = isThemed.invoke(instance);
            if (Boolean.TRUE.equals(result)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void hookUxMonoResourceBypass(ClassLoader loader, HookSpec spec) {
        try {
            Class<?> helperClass = findClass(loader, spec.helperClassName);
            Method method = helperClass.getDeclaredMethod(
                    spec.helperMethodName, Context.class, ComponentName.class, String.class);
            method.setAccessible(true);

            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object contextArg = chain.getArg(0);
                        Object componentArg = chain.getArg(1);
                        if (isInMonoChromeAssembly()
                                && contextArg instanceof Context
                                && componentArg instanceof ComponentName
                                && shouldUseNativeMono(
                                        (Context) contextArg, (ComponentName) componentArg)) {
                            ComponentName component = (ComponentName) componentArg;
                            String key = component.flattenToShortString();
                            if (loggedSuppressedUxMonoComponents.add(key)) {
                                log("suppressed ColorOS built-in mono resource for native APK mono: "
                                        + key);
                            }
                            return null;
                        }
                        return chain.proceed();
                    });
            log("hooked " + spec.helperClassName + "#" + spec.helperMethodName);
        } catch (Throwable t) {
            log("Ux mono resource hook skipped for " + spec.name + ": " + t);
        }

        try {
            Class<?> utilClass = findClass(loader, spec.utilClassName);
            Method method = utilClass.getDeclaredMethod(
                    spec.pathLoaderMethodName, String.class, Resources.class);
            method.setAccessible(true);

            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object pathArg = chain.getArg(0);
                        ComponentName component = currentMonoChromeComponent.get();
                        if (isInMonoChromeAssembly()
                                && pathArg instanceof String
                                && component != null
                                && isMonoPathForComponent((String) pathArg, component)
                                && shouldUseNativeMono(
                                        currentMonoChromeContext.get(), component)) {
                            String key = component.flattenToShortString();
                            if (loggedSuppressedPathComponents.add(key)) {
                                log("suppressed ColorOS data mono resource for native APK mono: "
                                        + key);
                            }
                            return null;
                        }
                        return chain.proceed();
                    });
            log("hooked " + spec.utilClassName + "#" + spec.pathLoaderMethodName);
        } catch (Throwable t) {
            log("Ux mono path hook skipped for " + spec.name + ": " + t);
        }
    }

    private boolean isMonoPathForComponent(String path, ComponentName component) {
        if (path == null || component == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);
        String pkg = component.getPackageName();
        if (pkg == null || !lower.contains("/" + pkg.toLowerCase(Locale.US) + "/")) {
            return false;
        }
        int index = lower.lastIndexOf('/');
        String name = index >= 0 ? lower.substring(index + 1) : lower;
        return name.startsWith("monochrome") || name.startsWith("dialer_monochrome");
    }

    private void hookNativeMonochromeResult() {
        if (nativeMonochromeHookInstalled) {
            return;
        }
        try {
            Method method = AdaptiveIconDrawable.class.getDeclaredMethod("getMonochrome");
            method.setAccessible(true);

            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof Drawable && isInMonoChromeAssembly()) {
                            nativeMonoFallbackDrawables.add((Drawable) result);
                            ComponentName component = currentMonoChromeComponent.get();
                            if (component != null) {
                                String key = component.flattenToShortString();
                                if (loggedNativeMonoComponents.add(key)) {
                                    log("marked native APK mono fallback: " + key);
                                }
                            }
                        }
                        return result;
                    });
            nativeMonochromeHookInstalled = true;
            log("hooked AdaptiveIconDrawable.getMonochrome");
        } catch (Throwable t) {
            log("Native monochrome hook skipped: " + t);
        }
    }

    private void hookMorphMono(ClassLoader loader, HookSpec spec) {
        try {
            Class<?> utilClass = findClass(loader, spec.utilClassName);
            Class<?> iconConfigClass = findClass(loader, spec.iconConfigClassName);
            Method method = utilClass.getDeclaredMethod(
                    spec.utilMethodName, Context.class, Drawable.class, iconConfigClass, boolean.class);
            method.setAccessible(true);

            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object special = chain.getArg(3);
                        Object drawable = chain.getArg(1);
                        Object contextArg = chain.getArg(0);
                        ComponentName component = currentMonoChromeComponent.get();
                        Context context = contextArg instanceof Context
                                ? (Context) contextArg : currentMonoChromeContext.get();
                        if (Boolean.TRUE.equals(special)
                                && isInMonoChromeAssembly()
                                && shouldUseNativeMono(context, component)
                                && ((drawable instanceof Drawable
                                        && isNativeMonoFallbackDrawable((Drawable) drawable))
                                        || component != null)) {
                            Object[] args = chain.getArgs().toArray(new Object[0]);
                            args[3] = Boolean.FALSE;
                            String key = component != null
                                    ? component.flattenToShortString() : "<unknown>";
                            if (loggedDisabledFilterComponents.add(key)) {
                                log("disabled ColorOS special mono filter for native APK mono: " + key);
                            }
                            return chain.proceed(args);
                        }
                        return chain.proceed();
                    });
            log("hooked " + spec.utilClassName + "#" + spec.utilMethodName);
        } catch (Throwable t) {
            log("Morph mono hook skipped for " + spec.name + ": " + t);
        }
    }

    private void hookFrameworkDarkFilter() {
        if (frameworkDarkFilterHookInstalled) {
            return;
        }
        try {
            Class<?> cls = Class.forName("android.app.OplusUXIconLoadHelper");
            Method method = cls.getDeclaredMethod("setDarkFilterToDrawable", Drawable.class);
            method.setAccessible(true);
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (isInMonoChromeAssembly()
                                && shouldUseNativeMono(currentMonoChromeContext.get(),
                                        currentMonoChromeComponent.get())) {
                            return null;
                        }
                        return chain.proceed();
                    });
            frameworkDarkFilterHookInstalled = true;
            log("hooked android.app.OplusUXIconLoadHelper#setDarkFilterToDrawable");
        } catch (Throwable t) {
            log("Framework dark filter hook skipped: " + t);
        }
    }

    private boolean ensureNativeMonoOnBitmapInfo(ClassLoader loader, Context context,
            ComponentName component, Object bitmapInfo, Object iconFactory, int maxContentPx) {
        if (context == null || component == null || bitmapInfo == null || iconFactory == null) {
            return false;
        }
        int targetMaxContentPx = Math.max(1, maxContentPx);
        Integer rememberedSize = bitmapInfoNativeMonoSizes.get(bitmapInfo);
        if (rememberedSize != null && rememberedSize.intValue() == targetMaxContentPx
                && getMonoBitmap(bitmapInfo) != null) {
            return false;
        }
        try {
            Drawable nativeDrawable = loadNativeMonoAdaptiveDrawable(context, component);
            if (nativeDrawable == null) {
                return false;
            }
            Class<?> baseIconFactoryClass =
                    findClass(loader, "com.android.launcher3.icons.BaseIconFactory");
            Method setMonoIcon = findMethodInHierarchy(
                    bitmapInfo.getClass(), "setMonoIcon", Bitmap.class, baseIconFactoryClass);
            setMonoIcon.setAccessible(true);
            Bitmap frameBitmap = getBitmapInfoIcon(bitmapInfo);
            Bitmap monoBitmap = createRawNativeMonoBitmap(nativeDrawable, frameBitmap, context);
            if (monoBitmap == null) {
                Method getMonochromeDrawable = findMethodInHierarchy(
                        iconFactory.getClass(), "getMonochromeDrawable", Drawable.class);
                getMonochromeDrawable.setAccessible(true);
                Object monoDrawable = getMonochromeDrawable.invoke(iconFactory, nativeDrawable);
                if (!(monoDrawable instanceof Drawable)) {
                    return false;
                }

                Method createIconBitmap = findMethodInHierarchy(iconFactory.getClass(),
                        "createIconBitmap", Drawable.class, float.class, int.class);
                createIconBitmap.setAccessible(true);
                Object createdBitmap = createIconBitmap.invoke(
                        iconFactory, monoDrawable, Float.valueOf(1.0f), Integer.valueOf(1));
                if (!(createdBitmap instanceof Bitmap)) {
                    return false;
                }
                monoBitmap = (Bitmap) createdBitmap;
            }
            Bitmap fittedMono = fitAlphaContentToTargetSize(
                    monoBitmap, targetMaxContentPx, frameBitmap);
            setMonoIcon.invoke(bitmapInfo, fittedMono, iconFactory);
            if (replaceWhiteLayerWithIconFrame(bitmapInfo, frameBitmap)) {
                String key = component.flattenToShortString();
                if (loggedMatchedFrameComponents.add(key)) {
                    log("matched native themed icon frame to ColorOS uxicon bitmap: " + key
                            + " frame=" + frameBitmap.getWidth() + "x" + frameBitmap.getHeight());
                }
            }
            bitmapInfoNativeMonoSizes.put(bitmapInfo, Integer.valueOf(targetMaxContentPx));
            return true;
        } catch (Throwable t) {
            log("Native mono attach failed for " + component.flattenToShortString() + ": " + t);
            return false;
        }
    }

    private void attachNativeMonoToMorphRequest(ClassLoader loader, Context context, Object request) {
        if (request == null || context == null) {
            return;
        }
        try {
            Object itemInfo = getFieldValue(request, "itemInfo");
            Object bitmapInfo = getFieldValue(request, "bitmapInfo");
            ComponentName component = getTargetComponent(itemInfo);
            if (bitmapInfo == null || !shouldUseNativeMono(context, component)) {
                return;
            }
            rememberBitmapInfoComponent(bitmapInfo, component);
            int maxContentPx = getMorphNativeMonoMaxPx(
                    context, readFirstIntField(request, 1, "spanX", "mSpanX", "cellSpanX"),
                    readFirstIntField(request, 1, "spanY", "mSpanY", "cellSpanY"));
            Object iconFactory = obtainLauncherIcons(loader, context);
            try {
                ensureNativeMonoOnBitmapInfo(
                        loader, context, component, bitmapInfo, iconFactory, maxContentPx);
            } finally {
                recycleIconFactory(iconFactory);
            }
        } catch (Throwable t) {
            log("Morph request native mono attach skipped: " + t);
        }
    }

    private Bitmap getMonoBitmap(Object bitmapInfo) {
        if (bitmapInfo == null) {
            return null;
        }
        try {
            Method method = findMethodInHierarchy(bitmapInfo.getClass(), "getMono");
            method.setAccessible(true);
            Object result = method.invoke(bitmapInfo);
            if (result instanceof Bitmap) {
                return (Bitmap) result;
            }
        } catch (Throwable ignored) {
        }
        try {
            Field field = findFieldInHierarchy(bitmapInfo.getClass(), "mMono");
            field.setAccessible(true);
            Object result = field.get(bitmapInfo);
            return result instanceof Bitmap ? (Bitmap) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object invokeBitmapInfoNewIcon(Object bitmapInfo, Context context, int flags) {
        if (bitmapInfo == null || context == null) {
            return null;
        }
        Boolean previous = forcingBitmapInfoNewIcon.get();
        forcingBitmapInfoNewIcon.set(Boolean.TRUE);
        try {
            Method newIcon = findMethodInHierarchy(
                    bitmapInfo.getClass(), "newIcon", Context.class, int.class);
            newIcon.setAccessible(true);
            return newIcon.invoke(bitmapInfo, context, Integer.valueOf(flags));
        } catch (Throwable t) {
            log("BitmapInfo themed newIcon failed: " + t);
            return null;
        } finally {
            restoreThreadLocal(forcingBitmapInfoNewIcon, previous);
        }
    }

    private void rememberBitmapInfoComponent(Object bitmapInfo, ComponentName component) {
        if (bitmapInfo == null || component == null) {
            return;
        }
        bitmapInfoComponents.put(bitmapInfo, component);
    }

    private ComponentName getRememberedBitmapInfoComponent(Object bitmapInfo) {
        if (bitmapInfo == null) {
            return null;
        }
        return bitmapInfoComponents.get(bitmapInfo);
    }

    private NativeMonoConfig getNativeMonoConfig(Context context) {
        long now = System.currentTimeMillis();
        NativeMonoConfig config = cachedNativeMonoConfig;
        if (now - lastNativeMonoConfigReadMs < CONFIG_CACHE_MS) {
            return config;
        }

        NativeMonoConfig updated = NativeMonoConfig.defaults();
        boolean hasConfigFile = readConfigFiles(updated);
        if (!hasConfigFile && context != null) {
            try {
                updated.normalPx = clampNormalPx(Settings.Global.getInt(
                        context.getContentResolver(), SETTING_NORMAL_PX, updated.normalPx));
                updated.morph1x2Px = Math.max(1, Settings.Global.getInt(
                        context.getContentResolver(), SETTING_MORPH_1X2_PX, updated.morph1x2Px));
                updated.morph2x1Px = Math.max(1, Settings.Global.getInt(
                        context.getContentResolver(), SETTING_MORPH_2X1_PX, updated.morph2x1Px));
                updated.morph2x2Px = Math.max(1, Settings.Global.getInt(
                        context.getContentResolver(), SETTING_MORPH_2X2_PX, updated.morph2x2Px));
            } catch (Throwable ignored) {
            }
        }

        cachedNativeMonoConfig = updated;
        lastNativeMonoConfigReadMs = now;
        return updated;
    }

    private boolean readConfigFiles(NativeMonoConfig config) {
        for (String path : CONFIG_PATHS) {
            File file = new File(path);
            if (!file.isFile()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int index = line.indexOf('=');
                    if (index <= 0) {
                        continue;
                    }
                    String key = line.substring(0, index).trim();
                    int value = parsePositiveInt(line.substring(index + 1).trim(), -1);
                    if (value <= 0) {
                        continue;
                    }
                    if ("normal_px".equals(key)) {
                        config.normalPx = clampNormalPx(value);
                    } else if ("morph_1x2_px".equals(key)) {
                        config.morph1x2Px = value;
                    } else if ("morph_2x1_px".equals(key)) {
                        config.morph2x1Px = value;
                    } else if ("morph_2x2_px".equals(key)) {
                        config.morph2x2Px = value;
                    }
                }
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private int getMorphNativeMonoMaxPx(Context context, int spanX, int spanY) {
        NativeMonoConfig config = getNativeMonoConfig(context);
        if (spanX == 2 && spanY == 2) {
            return config.morph2x2Px;
        }
        if (spanX == 1 && spanY == 2) {
            return config.morph1x2Px;
        }
        if (spanX == 2 && spanY == 1) {
            return config.morph2x1Px;
        }
        return config.normalPx;
    }

    private int readFirstIntField(Object target, int fallback, String... names) {
        if (target == null || names == null) {
            return fallback;
        }
        for (String name : names) {
            Object value = getFieldValue(target, name);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }
        return fallback;
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Integer) {
            return ((Integer) value).intValue();
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return fallback;
    }

    private static int clampNormalPx(int value) {
        if (value < MIN_NATIVE_MONO_CONTENT_PX) {
            return DEFAULT_NATIVE_MONO_CONTENT_PX;
        }
        if (value > MAX_NATIVE_MONO_CONTENT_PX) {
            return MAX_NATIVE_MONO_CONTENT_PX;
        }
        return value;
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private Bitmap getBitmapInfoIcon(Object bitmapInfo) {
        if (bitmapInfo == null) {
            return null;
        }
        Object icon = getFieldValue(bitmapInfo, "icon");
        if (icon instanceof Bitmap) {
            return (Bitmap) icon;
        }
        Object bitmap = getFieldValue(bitmapInfo, "bitmap");
        if (bitmap instanceof Bitmap) {
            return (Bitmap) bitmap;
        }
        return null;
    }

    private Bitmap createRawNativeMonoBitmap(Drawable nativeDrawable, Bitmap frameBitmap,
            Context context) {
        if (!(nativeDrawable instanceof AdaptiveIconDrawable)) {
            return null;
        }
        Drawable monoDrawable;
        try {
            monoDrawable = ((AdaptiveIconDrawable) nativeDrawable).getMonochrome();
        } catch (Throwable ignored) {
            return null;
        }
        if (monoDrawable == null) {
            return null;
        }

        int width = 0;
        int height = 0;
        int density = Bitmap.DENSITY_NONE;
        if (frameBitmap != null && !frameBitmap.isRecycled()
                && frameBitmap.getWidth() > 0 && frameBitmap.getHeight() > 0) {
            width = frameBitmap.getWidth();
            height = frameBitmap.getHeight();
            density = frameBitmap.getDensity();
        }
        if ((width <= 0 || height <= 0) && context != null) {
            int fallbackSize = Math.max(1, Math.round(
                    48.0f * context.getResources().getDisplayMetrics().density));
            width = fallbackSize;
            height = fallbackSize;
        }
        if (width <= 0 || height <= 0) {
            width = Math.max(1, monoDrawable.getIntrinsicWidth());
            height = Math.max(1, monoDrawable.getIntrinsicHeight());
        }
        if (width <= 0 || height <= 0) {
            return null;
        }

        Bitmap output;
        try {
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable ignored) {
            return null;
        }
        output.setDensity(density);
        Canvas canvas = new Canvas(output);
        Rect oldBounds = new Rect(monoDrawable.getBounds());
        try {
            monoDrawable.setBounds(0, 0, width, height);
            monoDrawable.draw(canvas);
        } catch (Throwable ignored) {
            try {
                output.recycle();
            } catch (Throwable ignoredRecycle) {
            }
            output = null;
        } finally {
            try {
                monoDrawable.setBounds(oldBounds);
            } catch (Throwable ignoredRestore) {
            }
        }
        if (output == null || findAlphaBounds(output) == null) {
            if (output != null) {
                try {
                    output.recycle();
                } catch (Throwable ignoredRecycle) {
                }
            }
            return null;
        }
        return output;
    }

    private Bitmap fitAlphaContentToTargetSize(Bitmap bitmap, int targetContentSize,
            Bitmap frameBitmap) {
        if (bitmap == null || bitmap.isRecycled() || targetContentSize <= 0) {
            return bitmap;
        }
        int sourceWidth = bitmap.getWidth();
        int sourceHeight = bitmap.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return bitmap;
        }
        int width = sourceWidth;
        int height = sourceHeight;
        int density = bitmap.getDensity();
        if (frameBitmap != null && !frameBitmap.isRecycled()
                && frameBitmap.getWidth() > 0 && frameBitmap.getHeight() > 0) {
            width = frameBitmap.getWidth();
            height = frameBitmap.getHeight();
            density = frameBitmap.getDensity();
        }

        Bitmap readable = bitmap;
        boolean copiedReadable = false;
        try {
            bitmap.getPixel(0, 0);
        } catch (Throwable ignored) {
            try {
                readable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                copiedReadable = readable != null;
            } catch (Throwable ignoredCopy) {
                return bitmap;
            }
        }
        if (readable == null || readable.isRecycled()) {
            return bitmap;
        }

        Rect content = findAlphaBounds(readable);
        if (content == null) {
            if (copiedReadable) {
                try {
                    readable.recycle();
                } catch (Throwable ignored) {
                }
            }
            return bitmap;
        }
        int contentWidth = content.width();
        int contentHeight = content.height();
        int fittedTargetContentSize = resolveFittedTargetContentSize(
                targetContentSize, width, height);
        int longest = Math.max(contentWidth, contentHeight);
        float scale = longest > 0 ? fittedTargetContentSize / (float) longest : 1.0f;
        scale = Math.min(scale, width / (float) Math.max(1, contentWidth));
        scale = Math.min(scale, height / (float) Math.max(1, contentHeight));
        int targetWidth = Math.max(1, Math.round(contentWidth * scale));
        int targetHeight = Math.max(1, Math.round(contentHeight * scale));
        int left = (width - targetWidth) / 2;
        int top = (height - targetHeight) / 2;
        Bitmap output;
        try {
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
        } catch (Throwable ignored) {
            return bitmap;
        }
        output.setDensity(density);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        try {
            canvas.drawBitmap(readable, content, new Rect(left, top, left + targetWidth,
                    top + targetHeight), paint);
        } catch (Throwable ignored) {
            if (copiedReadable) {
                try {
                    readable.recycle();
                } catch (Throwable ignoredRecycle) {
                }
            }
            return bitmap;
        }
        if (copiedReadable) {
            try {
                readable.recycle();
            } catch (Throwable ignored) {
            }
        }
        return output;
    }

    private int resolveFittedTargetContentSize(int configuredContentSize, int frameWidth,
            int frameHeight) {
        if (configuredContentSize < MIN_NATIVE_MONO_CONTENT_PX
                || configuredContentSize > MAX_NATIVE_MONO_CONTENT_PX) {
            return configuredContentSize;
        }
        int frameSize = Math.max(1, Math.min(frameWidth, frameHeight));
        if (frameSize <= NORMAL_CONTENT_REFERENCE_FRAME_PX) {
            return configuredContentSize;
        }
        return Math.max(configuredContentSize, Math.round(
                configuredContentSize * frameSize / (float) NORMAL_CONTENT_REFERENCE_FRAME_PX));
    }

    private Bitmap ensureBitmapMatchesFrameSize(Bitmap bitmap, Bitmap frameBitmap) {
        if (bitmap == null || bitmap.isRecycled() || frameBitmap == null
                || frameBitmap.isRecycled() || frameBitmap.getWidth() <= 0
                || frameBitmap.getHeight() <= 0) {
            return bitmap;
        }
        int width = frameBitmap.getWidth();
        int height = frameBitmap.getHeight();
        if (bitmap.getWidth() == width && bitmap.getHeight() == height) {
            return bitmap;
        }
        Bitmap output;
        try {
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
        } catch (Throwable ignored) {
            return bitmap;
        }
        output.setDensity(frameBitmap.getDensity());
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        try {
            canvas.drawBitmap(bitmap, null, new Rect(0, 0, width, height), paint);
            return output;
        } catch (Throwable ignored) {
            try {
                output.recycle();
            } catch (Throwable ignoredRecycle) {
            }
            return bitmap;
        }
    }

    private boolean replaceWhiteLayerWithIconFrame(Object bitmapInfo, Bitmap frameBitmap) {
        if (bitmapInfo == null) {
            return false;
        }
        Bitmap frameLayer = buildWhiteLayerFromIconAlpha(frameBitmap);
        if (frameLayer == null) {
            return false;
        }
        try {
            Field field = findFieldInHierarchy(bitmapInfo.getClass(), "mWhiteShadowLayer");
            field.setAccessible(true);
            field.set(bitmapInfo, frameLayer);
            return true;
        } catch (Throwable ignored) {
            try {
                frameLayer.recycle();
            } catch (Throwable ignoredRecycle) {
            }
            return false;
        }
    }

    private Bitmap buildWhiteLayerFromIconAlpha(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return null;
        }
        Bitmap readable = bitmap;
        boolean copiedReadable = false;
        try {
            bitmap.getPixel(0, 0);
        } catch (Throwable ignored) {
            try {
                readable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                copiedReadable = readable != null;
            } catch (Throwable ignoredCopy) {
                return null;
            }
        }
        if (readable == null || readable.isRecycled()) {
            return null;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Bitmap output;
        try {
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (Throwable ignored) {
            if (copiedReadable) {
                try {
                    readable.recycle();
                } catch (Throwable ignoredRecycle) {
                }
            }
            return null;
        }
        output.setDensity(bitmap.getDensity());

        int[] pixels = new int[width];
        try {
            for (int y = 0; y < height; y++) {
                readable.getPixels(pixels, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    int alpha = Color.alpha(pixels[x]);
                    pixels[x] = alpha == 0 ? Color.TRANSPARENT
                            : Color.argb(alpha, 255, 255, 255);
                }
                output.setPixels(pixels, 0, width, 0, y, width, 1);
            }
        } catch (Throwable ignored) {
            try {
                output.recycle();
            } catch (Throwable ignoredRecycle) {
            }
            output = null;
        }
        if (copiedReadable) {
            try {
                readable.recycle();
            } catch (Throwable ignoredRecycle) {
            }
        }
        return output;
    }

    private Rect findAlphaBounds(Bitmap bitmap) {
        int maxAlpha = findMaxAlpha(bitmap);
        if (maxAlpha <= 0) {
            return null;
        }
        int threshold = Math.max(MIN_VISIBLE_CONTENT_ALPHA,
                Math.round(maxAlpha * VISIBLE_CONTENT_ALPHA_RATIO));
        Rect visibleBounds = findAlphaBounds(bitmap, threshold);
        if (visibleBounds != null) {
            return visibleBounds;
        }
        return findAlphaBounds(bitmap, 1);
    }

    private int findMaxAlpha(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int maxAlpha = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha;
                try {
                    alpha = Color.alpha(bitmap.getPixel(x, y));
                } catch (Throwable ignored) {
                    return 0;
                }
                if (alpha > maxAlpha) {
                    maxAlpha = alpha;
                }
            }
        }
        return maxAlpha;
    }

    private Rect findAlphaBounds(Bitmap bitmap, int minAlpha) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha;
                try {
                    alpha = Color.alpha(bitmap.getPixel(x, y));
                } catch (Throwable ignored) {
                    return null;
                }
                if (alpha < minAlpha) {
                    continue;
                }
                if (x < left) {
                    left = x;
                }
                if (x > right) {
                    right = x;
                }
                if (y < top) {
                    top = y;
                }
                if (y > bottom) {
                    bottom = y;
                }
            }
        }
        if (right < left || bottom < top) {
            return null;
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private Object obtainLauncherIcons(ClassLoader loader, Context context) {
        try {
            Class<?> launcherIconsClass = findClass(loader, "com.android.launcher3.icons.LauncherIcons");
            Method obtain = launcherIconsClass.getDeclaredMethod("obtain", Context.class);
            obtain.setAccessible(true);
            return obtain.invoke(null, context);
        } catch (Throwable t) {
            log("obtain LauncherIcons failed: " + t);
            return null;
        }
    }

    private void recycleIconFactory(Object iconFactory) {
        if (iconFactory == null) {
            return;
        }
        try {
            Method recycle = findMethodInHierarchy(iconFactory.getClass(), "recycle");
            recycle.setAccessible(true);
            recycle.invoke(iconFactory);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Method close = findMethodInHierarchy(iconFactory.getClass(), "close");
            close.setAccessible(true);
            close.invoke(iconFactory);
        } catch (Throwable ignored) {
        }
    }

    private ComponentName getTargetComponent(Object itemInfo) {
        if (itemInfo == null) {
            return null;
        }
        try {
            Method method = findMethodInHierarchy(itemInfo.getClass(), "getMTargetComponent");
            method.setAccessible(true);
            Object component = method.invoke(itemInfo);
            if (component instanceof ComponentName) {
                return (ComponentName) component;
            }
        } catch (Throwable ignored) {
        }
        Object component = getFieldValue(itemInfo, "componentName");
        return component instanceof ComponentName ? (ComponentName) component : null;
    }

    private int getItemType(Object itemInfo) {
        try {
            Field field = findFieldInHierarchy(itemInfo.getClass(), "itemType");
            field.setAccessible(true);
            return field.getInt(itemInfo);
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private Object getFieldValue(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field field = findFieldInHierarchy(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean setFieldValue(Object target, String name, Object value) {
        if (target == null) {
            return false;
        }
        try {
            Field field = findFieldInHierarchy(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isNativeMonoFallbackDrawable(Drawable drawable) {
        synchronized (nativeMonoFallbackDrawables) {
            return nativeMonoFallbackDrawables.contains(drawable);
        }
    }

    private boolean isRecentNativeMonoDrawable(Drawable drawable) {
        synchronized (nativeMonoRecentTaskDrawables) {
            return nativeMonoRecentTaskDrawables.contains(drawable);
        }
    }

    private boolean isInMonoChromeAssembly() {
        return Boolean.TRUE.equals(inMonoChromeAssembly.get());
    }

    private static Class<?> findClass(ClassLoader loader, String className) throws ClassNotFoundException {
        return Class.forName(className, false, loader);
    }

    private static boolean hasClass(ClassLoader loader, String className) {
        try {
            findClass(loader, className);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field findFieldInHierarchy(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethodInHierarchy(Class<?> cls, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static <T> void restoreThreadLocal(ThreadLocal<T> threadLocal, T previousValue) {
        if (previousValue == null) {
            threadLocal.remove();
        } else {
            threadLocal.set(previousValue);
        }
    }

    private void log(String message) {
        log(Log.INFO, TAG, message);
    }

    private void log(Throwable throwable) {
        log(Log.ERROR, TAG, String.valueOf(throwable), throwable);
    }

    private static final class NativeMonoConfig {
        int normalPx;
        int morph1x2Px;
        int morph2x1Px;
        int morph2x2Px;

        static NativeMonoConfig defaults() {
            NativeMonoConfig config = new NativeMonoConfig();
            config.normalPx = DEFAULT_NATIVE_MONO_CONTENT_PX;
            config.morph1x2Px = DEFAULT_MORPH_1X2_CONTENT_PX;
            config.morph2x1Px = DEFAULT_MORPH_2X1_CONTENT_PX;
            config.morph2x2Px = DEFAULT_MORPH_2X2_CONTENT_PX;
            return config;
        }
    }

    private static final class HookSpec {
        final String name;
        final String factoryClassName;
        final String factoryMethodName;
        final String iconFormatClassName;
        final String iconConfigClassName;
        final String utilClassName;
        final String utilMethodName;
        final String helperClassName;
        final String helperMethodName;
        final String pathLoaderMethodName;

        HookSpec(String name, String factoryClassName, String factoryMethodName,
                String iconFormatClassName, String iconConfigClassName,
                String utilClassName, String utilMethodName,
                String helperClassName, String helperMethodName, String pathLoaderMethodName) {
            this.name = name;
            this.factoryClassName = factoryClassName;
            this.factoryMethodName = factoryMethodName;
            this.iconFormatClassName = iconFormatClassName;
            this.iconConfigClassName = iconConfigClassName;
            this.utilClassName = utilClassName;
            this.utilMethodName = utilMethodName;
            this.helperClassName = helperClassName;
            this.helperMethodName = helperMethodName;
            this.pathLoaderMethodName = pathLoaderMethodName;
        }

        boolean isAvailable(ClassLoader loader) {
            return hasClass(loader, factoryClassName)
                    && hasClass(loader, iconFormatClassName)
                    && hasClass(loader, iconConfigClassName)
                    && hasClass(loader, utilClassName)
                    && hasClass(loader, helperClassName);
        }

        private static boolean hasClass(ClassLoader loader, String className) {
            try {
                Class.forName(className, false, loader);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
