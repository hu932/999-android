package com.codex.shopeetaskhook;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.shopee.tw";
    private static final String TAG = "ShopeeTaskHook";
    private volatile boolean hooksInstalled = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) return;

        if (lpparam.processName != null && !lpparam.processName.equals(TARGET_PACKAGE)) {
            XposedBridge.log(TAG + ": skip non-main process: " + lpparam.processName);
            return;
        }

        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName + " pid=" + android.os.Process.myPid());

        try {
            hookApplicationOnCreate(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": handleLoadPackage error: " + t.getMessage());
        }
    }

    private void hookApplicationOnCreate(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Application app = (Application) param.thisObject;
                    if (!app.getPackageName().equals(TARGET_PACKAGE)) return;

                    XposedBridge.log(TAG + ": Application.onCreate");

                    app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                        private boolean initialized = false;

                        @Override public void onActivityCreated(Activity a, Bundle b) {}
                        @Override public void onActivityStarted(Activity a) {}
                        @Override
                        public void onActivityResumed(Activity a) {
                            if (initialized) return;
                            initialized = true;
                            XposedBridge.log(TAG + ": first Activity resumed: " + a.getClass().getName());
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                try {
                                    lateInit(app, lpparam.classLoader);
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": lateInit error: " + t.getMessage());
                                }
                            }, 3000);
                        }
                        @Override public void onActivityPaused(Activity a) {}
                        @Override public void onActivityStopped(Activity a) {}
                        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                        @Override public void onActivityDestroyed(Activity a) {}
                    });

                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": FATAL in afterHookedMethod: " + t.getMessage());
                }
            }
        });
    }

    private void lateInit(Application app, ClassLoader classLoader) {
        XposedBridge.log(TAG + ": lateInit start");

        try {
            PluginController.init(app, classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PluginController.init error: " + t.getMessage());
        }

        if (!hooksInstalled) {
            hooksInstalled = true;
            try {
                installOkHttpHook(classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": OkHttp hook error: " + t.getMessage());
            }
            try {
                installJsonHook(classLoader);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": JSON hook error: " + t.getMessage());
            }
        }

        XposedBridge.log(TAG + ": lateInit done");
    }

    private void installOkHttpHook(ClassLoader classLoader) {
        try {
            Class<?> builderClass = XposedHelpers.findClass("okhttp3.OkHttpClient$Builder", classLoader);
            Method buildMethod = builderClass.getDeclaredMethod("build");

            XposedBridge.hookMethod(buildMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        injectInterceptor(param.thisObject, classLoader);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": interceptor inject error: " + t.getMessage());
                    }
                }
            });

            XposedBridge.log(TAG + ": OkHttp hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": OkHttp hook failed: " + t.getMessage());
            installOkHttpFallbackHook(classLoader);
        }
    }

    @SuppressWarnings("unchecked")
    private void injectInterceptor(Object builder, ClassLoader classLoader) throws Throwable {
        Class<?> interceptorClass = XposedHelpers.findClass("okhttp3.Interceptor", classLoader);
        Class<?> chainClass = XposedHelpers.findClass("okhttp3.Interceptor$Chain", classLoader);

        Object proxy = Proxy.newProxyInstance(classLoader,
                new Class[]{interceptorClass},
                (obj, method, args) -> {
                    String methodName = method.getName();

                    if ("toString".equals(methodName)) return TAG + "_interceptor";
                    if ("hashCode".equals(methodName)) return System.identityHashCode(obj);
                    if ("equals".equals(methodName)) return obj == args[0];

                    if (!"intercept".equals(methodName)) return null;

                    Object chain = args[0];
                    Method requestMethod = chainClass.getMethod("request");
                    Object request = requestMethod.invoke(chain);

                    Method proceedMethod = chainClass.getMethod("proceed",
                            XposedHelpers.findClass("okhttp3.Request", classLoader));
                    Object response = proceedMethod.invoke(chain, request);

                    try {
                        inspectOkHttpResponse(response, request, classLoader);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": inspect error: " + t.getMessage());
                    }

                    return response;
                });

        List<Object> interceptors = null;
        String[] fieldNames = {"networkInterceptors", "interceptors"};
        for (String name : fieldNames) {
            try {
                Object field = XposedHelpers.getObjectField(builder, name);
                if (field instanceof List) {
                    interceptors = (List<Object>) field;
                    break;
                }
            } catch (Throwable ignored) {}
        }

        if (interceptors != null) {
            interceptors.add(proxy);
        } else {
            XposedBridge.log(TAG + ": no interceptor list found on builder");
        }
    }

    private void inspectOkHttpResponse(Object response, Object request, ClassLoader classLoader) throws Throwable {
        Object requestUrl = XposedHelpers.callMethod(request, "url");
        String urlString = requestUrl.toString();

        if (!urlString.contains("/api/v4/pdp/")) return;

        Object body = XposedHelpers.callMethod(response, "body");
        if (body == null) return;

        Object source = XposedHelpers.callMethod(body, "source");
        XposedHelpers.callMethod(source, "request", Long.MAX_VALUE);

        Object buffer = XposedHelpers.callMethod(source, "getBuffer");
        Object snapshot = XposedHelpers.callMethod(buffer, "snapshot");
        byte[] bytes = (byte[]) XposedHelpers.callMethod(snapshot, "toByteArray");

        if (bytes == null || bytes.length == 0) return;

        String jsonString = new String(bytes, StandardCharsets.UTF_8);

        PluginController controller = PluginController.getInstance();
        if (controller != null) {
            controller.inspectCapturedData(jsonString, urlString);
        }
    }

    private void installOkHttpFallbackHook(ClassLoader classLoader) {
        try {
            Class<?> realCallClass = null;
            String[] candidates = {
                "okhttp3.internal.connection.RealCall",
                "okhttp3.RealCall",
                "okhttp3.internal.http.RealInterceptorChain"
            };

            for (String name : candidates) {
                try {
                    realCallClass = XposedHelpers.findClass(name, classLoader);
                    break;
                } catch (Throwable ignored) {}
            }

            if (realCallClass == null) {
                XposedBridge.log(TAG + ": OkHttp fallback: no RealCall found");
                return;
            }

            for (Method m : realCallClass.getDeclaredMethods()) {
                if (m.getName().equals("getResponseWithInterceptorChain") ||
                    m.getName().equals("execute")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object response = param.getResult();
                                if (response == null) return;
                                Object request = XposedHelpers.callMethod(response, "request");
                                inspectOkHttpResponse(response, request, classLoader);
                            } catch (Throwable ignored) {}
                        }
                    });
                    XposedBridge.log(TAG + ": OkHttp fallback hook on " + m.getName());
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": OkHttp fallback failed: " + t.getMessage());
        }
    }

    private void installJsonHook(ClassLoader classLoader) {
        try {
            Class<?> jsonClass = classLoader.loadClass("org.json.JSONObject");

            XposedHelpers.findAndHookConstructor(jsonClass, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String raw = (String) param.args[0];
                                if (raw == null || raw.length() < 10240) return;

                                PluginController controller = PluginController.getInstance();
                                if (controller != null) {
                                    controller.inspectParsedJSON(raw);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });

            XposedBridge.log(TAG + ": JSON hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": JSON hook failed: " + t.getMessage());
        }
    }
}
