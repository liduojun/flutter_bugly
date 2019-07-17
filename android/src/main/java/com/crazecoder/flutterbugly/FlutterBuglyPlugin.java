package com.crazecoder.flutterbugly;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.crazecoder.flutterbugly.bean.BuglyInitResultInfo;
import com.crazecoder.flutterbugly.callback.UpgradeCallback;
import com.crazecoder.flutterbugly.utils.JsonUtil;
import com.crazecoder.flutterbugly.utils.MapUtil;
import com.tencent.bugly.Bugly;
import com.tencent.bugly.beta.Beta;
import com.tencent.bugly.beta.UpgradeInfo;
import com.tencent.bugly.beta.upgrade.UpgradeListener;
import com.tencent.bugly.crashreport.CrashReport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.core.content.FileProvider;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/**
 * FlutterBuglyPlugin
 */
public class FlutterBuglyPlugin implements MethodCallHandler {
    private Activity activity;
    private Result result;
    private boolean isResultSubmitted = false;
    private UpgradeInfo upgradeInfo;
    private static UpgradeCallback callback;


    public FlutterBuglyPlugin(Activity activity) {
        this.activity = activity;
    }

    /**
     * Plugin registration.
     */
    public static void registerWith(Registrar registrar) {
        final MethodChannel channel = new MethodChannel(registrar.messenger(), "crazecoder/flutter_bugly");
        FlutterBuglyPlugin plugin = new FlutterBuglyPlugin(registrar.activity());
        channel.setMethodCallHandler(plugin);
    }

    @Override
    public void onMethodCall(final MethodCall call, final Result result) {
        isResultSubmitted = false;
        this.result = result;
        if (call.method.equals("initBugly")) {
            if (call.hasArgument("appId")) {
                if (call.hasArgument("autoInit")) {
                    Beta.autoInit = false;
                }
                if (call.hasArgument("enableHotfix")) {
                    Beta.enableHotfix = call.argument("enableHotfix");
                }
                if (call.hasArgument("autoCheckUpgrade")) {
                    Beta.autoCheckUpgrade = call.argument("autoCheckUpgrade");
                }
                if (call.hasArgument("autoDownloadOnWifi")) {
                    Beta.autoDownloadOnWifi = call.argument("autoDownloadOnWifi");
                }
                if (call.hasArgument("initDelay")) {
                    int delay = call.argument("initDelay");
                    Beta.initDelay = delay * 1000;
                }
                if (call.hasArgument("enableNotification")) {
                    Beta.enableNotification = call.argument("enableNotification");
                }
                if (call.hasArgument("upgradeCheckPeriod")) {
                    int period = call.argument("upgradeCheckPeriod");
                    Beta.upgradeCheckPeriod = period * 1000;
                }
                if (call.hasArgument("showInterruptedStrategy")) {
                    Beta.showInterruptedStrategy = call.argument("showInterruptedStrategy");
                }
                if (call.hasArgument("canShowApkInfo")) {
                    Beta.canShowApkInfo = call.argument("canShowApkInfo");
                }
                Beta.canShowUpgradeActs.add(activity.getClass());
                /*在application中初始化时设置监听，监听策略的收取*/
                Beta.upgradeListener = new UpgradeListener() {
                    @Override
                    public void onUpgrade(int ret, UpgradeInfo strategy, boolean isManual, boolean isSilence) {
                        if (callback != null) {
                            callback.onUpgrade(strategy);
                        }
                    }
                };
                Bugly.init(activity.getApplicationContext(), call.argument("appId").toString(), BuildConfig.DEBUG);
                if (call.hasArgument("channel")) {
                    String channel = call.argument("channel");
                    if (!TextUtils.isEmpty(channel))
                        Bugly.setAppChannel(activity.getApplicationContext(), channel);
                }
                result(getResultBean(true, "Bugly 初始化成功"));
            } else {
                result(getResultBean(false, "Bugly key不能为空"));
            }
        } else if (call.method.equals("setUserId")) {
            if (call.hasArgument("userId")) {
                String userId = call.argument("userId");
                Bugly.setUserId(activity.getApplicationContext(), userId);
            }
            result(null);
        } else if (call.method.equals("setUserTag")) {
            if (call.hasArgument("userTag")) {
                Integer userTag = call.argument("userTag");
                if (userTag != null)
                    Bugly.setUserTag(activity.getApplicationContext(), userTag);
            }
            result(null);
        } else if (call.method.equals("putUserData")) {
            if (call.hasArgument("key") && call.hasArgument("value")) {
                String userDataKey = call.argument("key");
                String userDataValue = call.argument("value");
                Bugly.putUserData(activity.getApplicationContext(), userDataKey, userDataValue);
            }
            result(null);
        } else if (call.method.equals("checkUpgrade")) {
            boolean isManual = false;
            boolean isSilence = false;
            boolean useCache = true;
            if (call.hasArgument("isManual")) {
                isManual = call.argument("isManual");
            }
            if (call.hasArgument("isSilence")) {
                isSilence = call.argument("isSilence");
            }
            if (call.hasArgument("useCache")) {
                useCache = call.argument("useCache");
            }
            final boolean finalUseCache = useCache;
            callback = new UpgradeCallback() {
                @Override
                public void onUpgrade(UpgradeInfo strategy) {
                    if(finalUseCache){
                        if (strategy != null) {
                            upgradeInfo = strategy;
                        }
                        result(upgradeInfo);
                    }else {
                        result(strategy);
                    }
                }
            };
            Beta.checkUpgrade(isManual, isSilence);
        } else if (call.method.equals("getUpgradeInfo")) {
            UpgradeInfo strategy = Beta.getUpgradeInfo();
            result(strategy);
        } else if (call.method.equals("postCatchedException")) {
            String message = "";
            String detail = null;
            if (call.hasArgument("crash_message")) {
                message = call.argument("crash_message");
            }
            if (call.hasArgument("crash_detail")) {
                detail = call.argument("crash_detail");
            }
            if (TextUtils.isEmpty(detail)) return;
            String[] details = detail.split("#");
            List<StackTraceElement> elements = new ArrayList<>();
            for (String s : details) {
                if (!TextUtils.isEmpty(s)) {
                    String methodName = null;
                    String fileName = null;
                    int lineNum = -1;
                    String[] contents = s.split(" \\(");
                    if (contents.length > 0) {
                        methodName = contents[0];
                        if (contents.length < 2) {
                            break;
                        }
                        String packageContent = contents[1].replace(")", "");
                        String[] packageContentArray = packageContent.split("\\.dart:");
                        if (packageContentArray.length > 0) {
                            if (packageContentArray.length == 1) {
                                fileName = packageContentArray[0];
                            } else {
                                fileName = packageContentArray[0] + ".dart";
                                Pattern patternTrace = Pattern.compile("[1-9]\\d*");
                                Matcher m = patternTrace.matcher(packageContentArray[1]);
                                if (m.find()) {
                                    String lineNumStr = m.group();
                                    lineNum = Integer.parseInt(lineNumStr);
                                }
                            }
                        }
                    }
                    StackTraceElement element = new StackTraceElement("Dart", methodName, fileName, lineNum);
                    elements.add(element);
                }
            }
            Throwable throwable = new Throwable(message);
            if (elements.size() > 0) {
                StackTraceElement[] elementsArray = new StackTraceElement[elements.size()];
                throwable.setStackTrace(elements.toArray(elementsArray));
            }
            CrashReport.postCatchedException(throwable);
            result(null);
        } else if (call.method.equals("installApk")) {
            String filePath = call.argument("path");

            Log.i("apk", "开始执行安装: " + this.activity.getFilesDir() + filePath);
// 重新构造Uri：content://

            File fPath = new File(this.activity.getFilesDir() , "");
            File apkFile = new File(fPath , filePath);


            Log.i("apk", "安装包是否存在: " + apkFile.exists());

            if(!apkFile.exists()){
                Log.i("apk", "安装包是空的");
                result(null);
                return;
            }

            Log.i("apk", "开始执行安装: " + apkFile);
//            File apkFile = new File(this.activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filePath);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Log.w("apk", "版本大于 N ，开始使用 fileProvider 进行安装");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Uri contentUri = FileProvider.getUriForFile(
                        this.activity
                        , "cn.walkingpad.app.fileprovider"
                        , apkFile);
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
                Log.w("apk", "到这里了");
            } else {
                Log.w("apk", "正常进行安装");
                intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            }
            this.activity.startActivity(intent);
            Log.w("apk", "到End了");

//            result(null);
        } else {
            result.notImplemented();
            isResultSubmitted = true;
        }

    }



    private void result(Object object) {
        if (result != null && !isResultSubmitted) {
            if (object == null) {
                result.success(null);
            } else {
                result.success(JsonUtil.toJson(MapUtil.deepToMap(object)));
            }
            isResultSubmitted = true;
        }
    }

    private BuglyInitResultInfo getResultBean(boolean isSuccess, String msg) {
        BuglyInitResultInfo bean = new BuglyInitResultInfo();
        bean.setSuccess(isSuccess);
        bean.setMessage(msg);
        return bean;
    }
}