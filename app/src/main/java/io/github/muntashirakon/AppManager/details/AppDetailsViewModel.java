// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.details;

import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.core.content.pm.PermissionInfoCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkFormatException;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.AppManager.apk.ApkFile;
import io.github.muntashirakon.AppManager.appops.AppOpsManager;
import io.github.muntashirakon.AppManager.appops.AppOpsService;
import io.github.muntashirakon.AppManager.appops.AppOpsUtils;
import io.github.muntashirakon.AppManager.appops.OpEntry;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsComponentItem;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsItem;
import io.github.muntashirakon.AppManager.details.struct.AppDetailsPermissionItem;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.rules.RuleType;
import io.github.muntashirakon.AppManager.rules.compontents.ComponentUtils;
import io.github.muntashirakon.AppManager.rules.compontents.ComponentsBlocker;
import io.github.muntashirakon.AppManager.rules.struct.AppOpRule;
import io.github.muntashirakon.AppManager.rules.struct.RuleEntry;
import io.github.muntashirakon.AppManager.servermanager.PackageManagerCompat;
import io.github.muntashirakon.AppManager.servermanager.PermissionCompat;
import io.github.muntashirakon.AppManager.types.PackageChangeReceiver;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.FileUtils;
import io.github.muntashirakon.AppManager.utils.PermissionUtils;

import static io.github.muntashirakon.AppManager.appops.AppOpsManager.OP_NONE;
import static io.github.muntashirakon.AppManager.utils.PackageUtils.flagDisabledComponents;
import static io.github.muntashirakon.AppManager.utils.PackageUtils.flagMatchUninstalled;
import static io.github.muntashirakon.AppManager.utils.PackageUtils.flagSigningInfo;

public class AppDetailsViewModel extends AndroidViewModel {
    public static final String TAG = AppDetailsViewModel.class.getSimpleName();

    private final PackageManager mPackageManager;
    private PackageInfo packageInfo;
    private PackageInfo installedPackageInfo;
    private String packageName;
    private final Object blockerLocker = new Object();
    @GuardedBy("blockerLocker")
    private ComponentsBlocker blocker;
    private PackageIntentReceiver receiver;
    private String apkPath;
    private ApkFile apkFile;
    private int apkFileKey;
    private int userHandle;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AppDetailsFragment.SortOrder
    private int sortOrderComponents = (int) AppPref.get(AppPref.PrefKey.PREF_COMPONENTS_SORT_ORDER_INT);
    @AppDetailsFragment.SortOrder
    private int sortOrderAppOps = (int) AppPref.get(AppPref.PrefKey.PREF_APP_OP_SORT_ORDER_INT);
    @AppDetailsFragment.SortOrder
    private int sortOrderPermissions = (int) AppPref.get(AppPref.PrefKey.PREF_PERMISSIONS_SORT_ORDER_INT);

    private String searchQuery;
    private boolean waitForBlocker;
    private boolean isExternalApk = false;
    private final CountDownLatch packageInfoWatcher = new CountDownLatch(1);

    public AppDetailsViewModel(@NonNull Application application) {
        super(application);
        Log.d("ADVM", "New constructor called.");
        mPackageManager = application.getPackageManager();
        receiver = new PackageIntentReceiver(this);
        waitForBlocker = true;
    }

    @GuardedBy("blockerLocker")
    @Override
    public void onCleared() {
        Log.d("ADVM", "On Clear called for " + packageName);
        super.onCleared();
        executor.submit(() -> {
            synchronized (blockerLocker) {
                if (blocker != null) {
                    // To prevent commit if a mutable instance was created in the middle,
                    // set the instance read only again
                    blocker.setReadOnly();
                    blocker.close();
                }
            }
        });
        if (receiver != null) getApplication().unregisterReceiver(receiver);
        receiver = null;
        FileUtils.closeQuietly(apkFile);
        executor.shutdownNow();
    }

    @UiThread
    @NonNull
    public LiveData<PackageInfo> setPackage(@NonNull Uri packageUri, @Nullable String type) {
        MutableLiveData<PackageInfo> packageInfoLiveData = new MutableLiveData<>();
        executor.submit(() -> {
            try {
                Log.d("ADVM", "Package Uri is being set");
                isExternalApk = true;
                apkFileKey = ApkFile.createInstance(packageUri, type);
                apkFile = ApkFile.getInstance(apkFileKey);
                setPackageName(apkFile.getPackageName());
                File cachedApkFile = apkFile.getBaseEntry().getRealCachedFile();
                if (!cachedApkFile.canRead()) throw new Exception("Cannot read " + cachedApkFile);
                apkPath = cachedApkFile.getAbsolutePath();
                setPackageInfo(false);
                packageInfoLiveData.postValue(getPackageInfo());
            } catch (Throwable th) {
                Log.e("ADVM", "Could not fetch package info.", th);
                packageInfoLiveData.postValue(null);
            } finally {
                packageInfoWatcher.countDown();
            }
        });
        return packageInfoLiveData;
    }

    @UiThread
    @NonNull
    public LiveData<PackageInfo> setPackage(@NonNull String packageName) {
        MutableLiveData<PackageInfo> packageInfoLiveData = new MutableLiveData<>();
        executor.submit(() -> {
            try {
                Log.d("ADVM", "Package name is being set");
                isExternalApk = false;
                setPackageName(packageName);
                // TODO: 23/5/21 The app could be “data only”
                setPackageInfo(false);
                PackageInfo pi = getPackageInfo();
                if (pi == null) throw new ApkFile.ApkFileException("Package not installed.");
                apkFileKey = ApkFile.createInstance(pi.applicationInfo);
                apkFile = ApkFile.getInstance(apkFileKey);
                packageInfoLiveData.postValue(pi);
            } catch (Throwable th) {
                Log.e("ADVM", "Could not fetch package info.", th);
                packageInfoLiveData.postValue(null);
            } finally {
                packageInfoWatcher.countDown();
            }
        });
        return packageInfoLiveData;
    }

    @AnyThread
    public void setUserHandle(@UserIdInt int userHandle) {
        this.userHandle = userHandle;
    }

    @AnyThread
    public int getUserHandle() {
        return userHandle;
    }

    @AnyThread
    @GuardedBy("blockerLocker")
    private void setPackageName(String packageName) {
        if (this.packageName != null) return;
        Log.d("ADVM", "Package name is being set for " + packageName);
        this.packageName = packageName;
        if (isExternalApk) return;
        executor.submit(() -> {
            synchronized (blockerLocker) {
                try {
                    waitForBlocker = true;
                    if (blocker != null) {
                        // To prevent commit if a mutable instance was created in the middle,
                        // set the instance read only again
                        blocker.setReadOnly();
                        blocker.close();
                    }
                    blocker = ComponentsBlocker.getInstance(packageName, userHandle);
                } finally {
                    waitForBlocker = false;
                    blockerLocker.notifyAll();
                }
            }
        });
    }

    @AnyThread
    public String getPackageName() {
        return packageName;
    }

    @AnyThread
    public int getApkFileKey() {
        return apkFileKey;
    }

    @AnyThread
    @SuppressLint("SwitchIntDef")
    public void setSortOrder(@AppDetailsFragment.SortOrder int sortOrder, @AppDetailsFragment.Property int property) {
        switch (property) {
            case AppDetailsFragment.ACTIVITIES:
            case AppDetailsFragment.SERVICES:
            case AppDetailsFragment.RECEIVERS:
            case AppDetailsFragment.PROVIDERS:
                sortOrderComponents = sortOrder;
                AppPref.set(AppPref.PrefKey.PREF_COMPONENTS_SORT_ORDER_INT, sortOrder);
                break;
            case AppDetailsFragment.APP_OPS:
                sortOrderAppOps = sortOrder;
                AppPref.set(AppPref.PrefKey.PREF_APP_OP_SORT_ORDER_INT, sortOrder);
                break;
            case AppDetailsFragment.USES_PERMISSIONS:
                sortOrderPermissions = sortOrder;
                AppPref.set(AppPref.PrefKey.PREF_PERMISSIONS_SORT_ORDER_INT, sortOrder);
                break;
        }
    }

    @AnyThread
    @SuppressLint("SwitchIntDef")
    @AppDetailsFragment.SortOrder
    public int getSortOrder(@AppDetailsFragment.Property int property) {
        switch (property) {
            case AppDetailsFragment.ACTIVITIES:
            case AppDetailsFragment.SERVICES:
            case AppDetailsFragment.RECEIVERS:
            case AppDetailsFragment.PROVIDERS:
                return sortOrderComponents;
            case AppDetailsFragment.APP_OPS:
                return sortOrderAppOps;
            case AppDetailsFragment.USES_PERMISSIONS:
                return sortOrderPermissions;
        }
        return AppDetailsFragment.SORT_BY_NAME;
    }

    @AnyThread
    public void setSearchQuery(String searchQuery) {
        this.searchQuery = searchQuery;
    }

    @AnyThread
    public String getSearchQuery() {
        return searchQuery;
    }

    @NonNull
    private final MutableLiveData<Integer> ruleApplicationStatus = new MutableLiveData<>();
    public static final int RULE_APPLIED = 0;
    public static final int RULE_NOT_APPLIED = 1;
    public static final int RULE_NO_RULE = 2;

    @UiThread
    public LiveData<Integer> getRuleApplicationStatus() {
        if (ruleApplicationStatus.getValue() == null) {
            executor.submit(this::setRuleApplicationStatus);
        }
        return ruleApplicationStatus;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    public void setRuleApplicationStatus() {
        if (packageName == null || isExternalApk) {
            ruleApplicationStatus.postValue(RULE_NO_RULE);
            return;
        }
        synchronized (blockerLocker) {
            waitForBlockerOrExit();
            final AtomicInteger newRuleApplicationStatus = new AtomicInteger();
            newRuleApplicationStatus.set(blocker.isRulesApplied() ? RULE_APPLIED : RULE_NOT_APPLIED);
            if (blocker.componentCount() == 0) newRuleApplicationStatus.set(RULE_NO_RULE);
            ruleApplicationStatus.postValue(newRuleApplicationStatus.get());
        }
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    public void updateRulesForComponent(String componentName, RuleType type) {
        if (isExternalApk) return;
        synchronized (blockerLocker) {
            waitForBlockerOrExit();
            blocker.setMutable();
            if (blocker.hasComponentName(componentName)) {
                // Component is in the list
                if (blocker.isComponentBlocked(componentName)) {
                    // Remove from the list
                    blocker.removeComponent(componentName);
                } else {
                    // The component isn't being blocked, simply delete it
                    blocker.deleteComponent(componentName);
                }
            } else {
                // Add to the list
                blocker.addComponent(componentName, type);
            }
            // Apply rules if global blocking enable or already applied
            if ((Boolean) AppPref.get(AppPref.PrefKey.PREF_GLOBAL_BLOCKING_ENABLED_BOOL)
                    || (ruleApplicationStatus.getValue() != null && RULE_APPLIED == ruleApplicationStatus.getValue())) {
                blocker.applyRules(true);
            }
            // Set new status
            setRuleApplicationStatus();
            // Commit changes
            blocker.commit();
            blocker.setReadOnly();
            // Update UI
            reloadComponents();
        }
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    public void addRules(List<? extends RuleEntry> entries, boolean forceApply) {
        if (isExternalApk) return;
        synchronized (blockerLocker) {
            waitForBlockerOrExit();
            blocker.setMutable();
            for (RuleEntry entry : entries) {
                String componentName = entry.name;
                if (blocker.hasComponentName(componentName)) {
                    // Remove from the list
                    blocker.removeComponent(componentName);
                }
                // Add to the list (again)
                blocker.addComponent(componentName, entry.type);
            }
            // Apply rules if global blocking enable or already applied
            if (forceApply || (Boolean) AppPref.get(AppPref.PrefKey.PREF_GLOBAL_BLOCKING_ENABLED_BOOL)
                    || (ruleApplicationStatus.getValue() != null && RULE_APPLIED == ruleApplicationStatus.getValue())) {
                blocker.applyRules(true);
            }
            // Set new status
            setRuleApplicationStatus();
            // Commit changes
            blocker.commit();
            blocker.setReadOnly();
            // Update UI
            reloadComponents();
        }
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    public void removeRules(List<? extends RuleEntry> entries, boolean forceApply) {
        if (isExternalApk) return;
        synchronized (blockerLocker) {
            waitForBlockerOrExit();
            blocker.setMutable();
            for (RuleEntry entry : entries) {
                String componentName = entry.name;
                if (blocker.hasComponentName(componentName)) {
                    // Remove from the list
                    blocker.removeComponent(componentName);
                }
            }
            // Apply rules if global blocking enable or already applied
            if (forceApply || (Boolean) AppPref.get(AppPref.PrefKey.PREF_GLOBAL_BLOCKING_ENABLED_BOOL)
                    || (ruleApplicationStatus.getValue() != null && RULE_APPLIED == ruleApplicationStatus.getValue())) {
                blocker.applyRules(true);
            }
            // Set new status
            setRuleApplicationStatus();
            // Commit changes
            blocker.commit();
            blocker.setReadOnly();
            // Update UI
            reloadComponents();
        }
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    public boolean togglePermission(final AppDetailsPermissionItem permissionItem) {
        if (isExternalApk) return false;
        int appOp = permissionItem.appOp;
        int uid = packageInfo.applicationInfo.uid;
        if (!permissionItem.isGranted) {
            // If not granted, grant permission
            if (appOp != OP_NONE) {
                try {
                    mAppOpsService.setMode(permissionItem.appOp, uid, packageName, AppOpsManager.MODE_ALLOWED);
                } catch (Exception ignore) {
                    return false;
                }
            }
            // Grant permission too
            try {
                PermissionCompat.grantPermission(packageName, permissionItem.name, userHandle);
            } catch (RemoteException ignore) {
                return false;
            }
        } else {
            if (appOp != OP_NONE) {
                try {
                    mAppOpsService.setMode(appOp, uid, packageName, AppOpsManager.MODE_IGNORED);
                } catch (Exception e) {
                    return false;
                }
            }
            // Revoke permission too
            try {
                PermissionCompat.revokePermission(packageName, permissionItem.name, userHandle);
            } catch (RemoteException e) {
                e.printStackTrace();
                return false;
            }
        }
        // Check if review is required and disable it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (permissionItem.permissionFlags
                & PermissionCompat.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
            try {
                PermissionCompat.updatePermissionFlags(permissionItem.name, packageName,
                        PermissionCompat.FLAG_PERMISSION_REVIEW_REQUIRED, 0, PermissionCompat
                                .getCheckAdjustPolicyFlagPermission(packageInfo.applicationInfo), userHandle);
            } catch (RemoteException ignore) {
                return false;
            }
        }
        permissionItem.isGranted = !permissionItem.isGranted;
        try {
            // Get new flags
            permissionItem.permissionFlags = PermissionCompat.getPermissionFlags(permissionItem.name, packageName,
                    userHandle);
        } catch (RemoteException ignore) {
        }
        executor.submit(() -> {
            synchronized (blockerLocker) {
                waitForBlockerOrExit();
                blocker.setMutable();
                if (appOp != OP_NONE) {
                    blocker.setAppOp(appOp, permissionItem.isGranted ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED);
                } else {
                    blocker.setPermission(permissionItem.name, permissionItem.isGranted, permissionItem.permissionFlags);
                }
                blocker.commit();
                blocker.setReadOnly();
                blockerLocker.notifyAll();
            }
        });
        return true;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    public boolean revokeDangerousPermissions() {
        if (isExternalApk) return false;
        AppDetailsPermissionItem permissionItem;
        List<AppDetailsPermissionItem> revokedPermissions = new ArrayList<>();
        boolean isSuccessful = true;
        synchronized (usesPermissionItems) {
            for (int i = 0; i < usesPermissionItems.size(); ++i) {
                permissionItem = usesPermissionItems.get(i);
                if (permissionItem.isDangerous && permissionItem.isGranted) {
                    try {
                        PermissionCompat.revokePermission(packageName, permissionItem.name, userHandle);
                        // Check if review is required and disable it
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (permissionItem.permissionFlags
                                & PermissionCompat.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                            try {
                                PermissionCompat.updatePermissionFlags(permissionItem.name, packageName,
                                        PermissionCompat.FLAG_PERMISSION_REVIEW_REQUIRED, 0, PermissionCompat
                                                .getCheckAdjustPolicyFlagPermission(packageInfo.applicationInfo),
                                        userHandle);
                            } catch (RemoteException ignore) {
                            }
                        }
                        permissionItem.isGranted = false;
                        try {
                            permissionItem.permissionFlags = PermissionCompat.getPermissionFlags(permissionItem.name,
                                    packageName, userHandle);
                        } catch (RemoteException ignore) {
                        }
                        usesPermissionItems.set(i, permissionItem);
                        revokedPermissions.add(permissionItem);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                        isSuccessful = false;
                        break;
                    }
                }
            }
        }
        // Save values to the blocking rules
        executor.submit(() -> {
            synchronized (blockerLocker) {
                waitForBlockerOrExit();
                blocker.setMutable();
                for (AppDetailsPermissionItem permItem : revokedPermissions) {
                    blocker.setPermission(permItem.name, permItem.isGranted, permItem.permissionFlags);
                }
                blocker.commit();
                blocker.setReadOnly();
                blockerLocker.notifyAll();
            }
        });
        return isSuccessful;
    }

    @NonNull
    private final AppOpsService mAppOpsService = new AppOpsService();

    @WorkerThread
    @GuardedBy("blockerLocker")
    public boolean setAppOp(int op, int mode) {
        if (isExternalApk) return false;
        try {
            // Set mode
            mAppOpsService.setMode(op, packageInfo.applicationInfo.uid, packageName, mode);
            executor.submit(() -> {
                synchronized (blockerLocker) {
                    waitForBlockerOrExit();
                    blocker.setMutable();
                    blocker.setAppOp(op, mode);
                    blocker.commit();
                    blocker.setReadOnly();
                    blockerLocker.notifyAll();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    public boolean resetAppOps() {
        if (isExternalApk) return false;
        try {
            mAppOpsService.resetAllModes(userHandle, packageName);
            executor.submit(this::loadAppOps);
            // Save values to the blocking rules
            executor.submit(() -> {
                synchronized (blockerLocker) {
                    waitForBlockerOrExit();
                    List<AppOpRule> appOpEntries = blocker.getAll(AppOpRule.class);
                    blocker.setMutable();
                    for (AppOpRule entry : appOpEntries)
                        blocker.removeEntry(entry);
                    blocker.commit();
                    blocker.setReadOnly();
                    blockerLocker.notifyAll();
                }
            });
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    public boolean ignoreDangerousAppOps() {
        if (isExternalApk) return false;
        AppDetailsItem appDetailsItem;
        OpEntry opEntry;
        String permName;
        final List<Integer> opItems = new ArrayList<>();
        boolean isSuccessful = true;
        for (int i = 0; i < appOpItems.size(); ++i) {
            appDetailsItem = appOpItems.get(i);
            opEntry = (OpEntry) appDetailsItem.vanillaItem;
            try {
                permName = AppOpsManager.opToPermission(opEntry.getOp());
                if (permName != null) {
                    PermissionInfo permissionInfo = mPackageManager.getPermissionInfo(permName, PackageManager.GET_META_DATA);
                    int basePermissionType = PermissionInfoCompat.getProtection(permissionInfo);
                    if (basePermissionType == PermissionInfo.PROTECTION_DANGEROUS) {
                        // Set mode
                        try {
                            mAppOpsService.setMode(opEntry.getOp(), packageInfo.applicationInfo.uid,
                                    packageName, AppOpsManager.MODE_IGNORED);
                            opItems.add(opEntry.getOp());
                            appDetailsItem.vanillaItem = new OpEntry(opEntry.getOp(),
                                    AppOpsManager.MODE_IGNORED, opEntry.getTime(),
                                    opEntry.getRejectTime(), opEntry.getDuration(),
                                    opEntry.getProxyUid(), opEntry.getProxyPackageName());
                            appOpItems.set(i, appDetailsItem);
                        } catch (Exception e) {
                            e.printStackTrace();
                            isSuccessful = false;
                            break;
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException | IllegalArgumentException | IndexOutOfBoundsException ignore) {
            }
        }
        // Save values to the blocking rules
        executor.submit(() -> {
            synchronized (blockerLocker) {
                waitForBlockerOrExit();
                blocker.setMutable();
                for (int op : opItems)
                    blocker.setAppOp(op, AppOpsManager.MODE_IGNORED);
                blocker.commit();
                blocker.setReadOnly();
                blockerLocker.notifyAll();
            }
        });
        return isSuccessful;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    public void applyRules() {
        if (isExternalApk) return;
        synchronized (blockerLocker) {
            waitForBlockerOrExit();
            boolean oldIsRulesApplied = blocker.isRulesApplied();
            blocker.setMutable();
            blocker.applyRules(!oldIsRulesApplied);
            blocker.commit();
            blocker.setReadOnly();
            reloadComponents();
            setRuleApplicationStatus();
            blockerLocker.notifyAll();
        }
    }

    @UiThread
    public LiveData<List<AppDetailsItem>> get(@AppDetailsFragment.Property int property) {
        switch (property) {
            case AppDetailsFragment.ACTIVITIES:
                return getActivities();
            case AppDetailsFragment.SERVICES:
                return getServices();
            case AppDetailsFragment.RECEIVERS:
                return getReceivers();
            case AppDetailsFragment.PROVIDERS:
                return getProviders();
            case AppDetailsFragment.APP_OPS:
                return getAppOps();
            case AppDetailsFragment.USES_PERMISSIONS:
                return getUsesPermissions();
            case AppDetailsFragment.PERMISSIONS:
                return getPermissions();
            case AppDetailsFragment.FEATURES:
                return getFeatures();
            case AppDetailsFragment.CONFIGURATIONS:
                return getConfigurations();
            case AppDetailsFragment.SIGNATURES:
                return getSignatures();
            case AppDetailsFragment.SHARED_LIBRARIES:
                return getSharedLibraries();
            case AppDetailsFragment.APP_INFO:
                return getAppInfo();
            case AppDetailsFragment.NONE:
        }
        return null;
    }

    @AnyThread
    public void load(@AppDetailsFragment.Property int property) {
        executor.submit(() -> {
            switch (property) {
                case AppDetailsFragment.ACTIVITIES:
                    loadActivities();
                    break;
                case AppDetailsFragment.SERVICES:
                    loadServices();
                    break;
                case AppDetailsFragment.RECEIVERS:
                    loadReceivers();
                    break;
                case AppDetailsFragment.PROVIDERS:
                    loadProviders();
                    break;
                case AppDetailsFragment.APP_OPS:
                    loadAppOps();
                    break;
                case AppDetailsFragment.USES_PERMISSIONS:
                    loadUsesPermissions();
                    break;
                case AppDetailsFragment.PERMISSIONS:
                    loadPermissions();
                    break;
                case AppDetailsFragment.FEATURES:
                    loadFeatures();
                    break;
                case AppDetailsFragment.CONFIGURATIONS:
                    loadConfigurations();
                    break;
                case AppDetailsFragment.SIGNATURES:
                    loadSignatures();
                    break;
                case AppDetailsFragment.SHARED_LIBRARIES:
                    loadSharedLibraries();
                    break;
                case AppDetailsFragment.APP_INFO:
                    loadAppInfo();
                case AppDetailsFragment.NONE:
                    break;
            }
        });
    }

    private final MutableLiveData<Boolean> isPackageExistLiveData = new MutableLiveData<>();
    private boolean isPackageExist = true;

    @UiThread
    public LiveData<Boolean> getIsPackageExistLiveData() {
        if (isPackageExistLiveData.getValue() == null)
            isPackageExistLiveData.setValue(isPackageExist);
        return isPackageExistLiveData;
    }

    @AnyThread
    public boolean isPackageExist() {
        return isPackageExist;
    }

    @NonNull
    private final MutableLiveData<Boolean> isPackageChanged = new MutableLiveData<>();

    @UiThread
    public LiveData<Boolean> getIsPackageChanged() {
        if (isPackageChanged.getValue() == null) {
            isPackageChanged.setValue(false);
        }
        return isPackageChanged;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    public void setIsPackageChanged() {
        setPackageInfo(true);
        if (isExternalApk || executor.isShutdown() || executor.isTerminated()) return;
        executor.submit(() -> {
            synchronized (blockerLocker) {
                try {
                    waitForBlockerOrExit();
                    // Reload app components
                    blocker.reloadComponents();
                } finally {
                    blockerLocker.notifyAll();
                }
            }
        });
    }

    @AnyThread
    public boolean getIsExternalApk() {
        return isExternalApk;
    }

    @AnyThread
    public int getSplitCount() {
        if (apkFile.isSplit()) return apkFile.getEntries().size() - 1;
        return 0;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    private void waitForBlockerOrExit() {
        if (isExternalApk) return;
        if (blocker == null) {
            try {
                while (waitForBlocker) blockerLocker.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
    }

    @WorkerThread
    private void reloadComponents() {
        executor.submit(this::loadActivities);
        executor.submit(this::loadServices);
        executor.submit(this::loadReceivers);
        executor.submit(this::loadProviders);
    }

    @SuppressLint("WrongConstant")
    @WorkerThread
    public void setPackageInfo(boolean reload) {
        // Package name cannot be null
        if (packageName == null) return;
        // Wait for component blocker to appear
        synchronized (blockerLocker) {
            waitForBlockerOrExit();
        }
        if (!reload && packageInfo != null) return;
        try {
            try {
                installedPackageInfo = PackageManagerCompat.getPackageInfo(packageName,
                        PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES | flagDisabledComponents
                                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS | flagMatchUninstalled
                                | PackageManager.GET_SERVICES | PackageManager.GET_CONFIGURATIONS | flagSigningInfo
                                | PackageManager.GET_SHARED_LIBRARY_FILES | PackageManager.GET_URI_PERMISSION_PATTERNS,
                        userHandle);
                if (!new File(installedPackageInfo.applicationInfo.publicSourceDir).exists()) {
                    throw new ApkFile.ApkFileException("App not installed. It only has data.");
                }
            } catch (Throwable e) {
                Log.e(TAG, e);
                installedPackageInfo = null;
            }
            if (isExternalApk) {
                // Do not get signatures via Android framework as it will simply return NULL without any clarifications.
                // All signatures are fetched using PackageUtils where a fallback method is used in case the PackageInfo
                // didn't load any signature. So, we should be safe from any harm.
                packageInfo = mPackageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_PERMISSIONS
                        | PackageManager.GET_ACTIVITIES | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                        | PackageManager.GET_SERVICES | flagDisabledComponents
                        | PackageManager.GET_CONFIGURATIONS | PackageManager.GET_SHARED_LIBRARY_FILES
                        | PackageManager.GET_URI_PERMISSION_PATTERNS);
                if (packageInfo == null) {
                    throw new PackageManager.NameNotFoundException("Package cannot be parsed");
                }
                if (installedPackageInfo == null) {
                    Log.d("ADVM", packageName + " not installed for user " + userHandle);
                }
                packageInfo.applicationInfo.sourceDir = apkPath;
                packageInfo.applicationInfo.publicSourceDir = apkPath;
            } else {
                packageInfo = installedPackageInfo;
                if (packageInfo == null) {
                    throw new PackageManager.NameNotFoundException("Package not installed");
                }
            }
            isPackageExistLiveData.postValue(isPackageExist = true);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, e);
            isPackageExistLiveData.postValue(isPackageExist = false);
        } catch (Throwable e) {
            Log.e(TAG, e);
        } finally {
            isPackageChanged.postValue(true);
        }
    }

    @WorkerThread
    @Nullable
    private PackageInfo getPackageInfoInternal() {
        try {
            packageInfoWatcher.await();
        } catch (InterruptedException e) {
            return null;
        }
        return packageInfo;
    }

    @AnyThread
    @Nullable
    public PackageInfo getPackageInfo() {
        return packageInfo;
    }

    @AnyThread
    @Nullable
    public PackageInfo getInstalledPackageInfo() {
        return installedPackageInfo;
    }

    @NonNull
    public LiveData<UserInfo> getUserInfo() {
        MutableLiveData<UserInfo> userInfoMutableLiveData = new MutableLiveData<>();
        executor.submit(() -> {
            final List<UserInfo> userInfoList;
            if (!isExternalApk && AppPref.isRootOrAdbEnabled()) {
                userInfoList = Users.getUsers();
            } else userInfoList = null;
            if (userInfoList != null && userInfoList.size() > 1) {
                for (UserInfo userInfo : userInfoList) {
                    if (userInfo.id == userHandle) {
                        userInfoMutableLiveData.postValue(userInfo);
                        break;
                    }
                }
            }
        });
        return userInfoMutableLiveData;
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> appInfo = new MutableLiveData<>();

    @UiThread
    private LiveData<List<AppDetailsItem>> getAppInfo() {
        if (appInfo.getValue() == null) {
            executor.submit(this::loadAppInfo);
        }
        return appInfo;
    }

    @WorkerThread
    private void loadAppInfo() {
        getPackageInfoInternal();
        if (packageInfo == null) {
            appInfo.postValue(null);
            return;
        }
        AppDetailsItem appDetailsItem = new AppDetailsItem(packageInfo);
        appDetailsItem.name = packageName;
        List<AppDetailsItem> appDetailsItems = Collections.singletonList(appDetailsItem);
        appInfo.postValue(appDetailsItems);
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> activities = new MutableLiveData<>();

    @UiThread
    private LiveData<List<AppDetailsItem>> getActivities() {
        if (activities.getValue() == null) {
            executor.submit(this::loadActivities);
        }
        return activities;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    private void loadActivities() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        if (getPackageInfoInternal() == null || packageInfo.activities == null) {
            activities.postValue(appDetailsItems);
            return;
        }
        for (ActivityInfo activityInfo : packageInfo.activities) {
            AppDetailsComponentItem appDetailsItem = new AppDetailsComponentItem(activityInfo);
            appDetailsItem.name = activityInfo.targetActivity == null ? activityInfo.name : activityInfo.targetActivity;
            synchronized (blockerLocker) {
                if (!isExternalApk) {
                    appDetailsItem.isBlocked = blocker.hasComponentName(activityInfo.name);
                }
            }
            appDetailsItem.isTracker = ComponentUtils.isTracker(activityInfo.name);
            if (TextUtils.isEmpty(searchQuery) || appDetailsItem.name.toLowerCase(Locale.ROOT).contains(searchQuery))
                appDetailsItems.add(appDetailsItem);
        }
        sortComponents(appDetailsItems);
        activities.postValue(appDetailsItems);
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> services = new MutableLiveData<>();

    @UiThread
    private LiveData<List<AppDetailsItem>> getServices() {
        if (services.getValue() == null) {
            executor.submit(this::loadServices);
        }
        return services;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    private void loadServices() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        if (getPackageInfoInternal() == null || packageInfo.services == null) {
            // There are no services
            services.postValue(appDetailsItems);
            return;
        }
        for (ServiceInfo serviceInfo : packageInfo.services) {
            AppDetailsComponentItem appDetailsItem = new AppDetailsComponentItem(serviceInfo);
            appDetailsItem.name = serviceInfo.name;
            synchronized (blockerLocker) {
                if (!isExternalApk) {
                    appDetailsItem.isBlocked = blocker.hasComponentName(serviceInfo.name);
                }
            }
            appDetailsItem.isTracker = ComponentUtils.isTracker(serviceInfo.name);
            if (TextUtils.isEmpty(searchQuery)
                    || appDetailsItem.name.toLowerCase(Locale.ROOT).contains(searchQuery))
                appDetailsItems.add(appDetailsItem);
        }
        sortComponents(appDetailsItems);
        services.postValue(appDetailsItems);
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> receivers = new MutableLiveData<>();

    @UiThread
    private LiveData<List<AppDetailsItem>> getReceivers() {
        if (receivers.getValue() == null) {
            executor.submit(this::loadReceivers);
        }
        return receivers;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    private void loadReceivers() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        if (getPackageInfoInternal() == null || packageInfo.receivers == null) {
            // There are no receivers
            receivers.postValue(appDetailsItems);
            return;
        }
        for (ActivityInfo activityInfo : packageInfo.receivers) {
            AppDetailsComponentItem appDetailsItem = new AppDetailsComponentItem(activityInfo);
            appDetailsItem.name = activityInfo.name;
            synchronized (blockerLocker) {
                if (!isExternalApk) {
                    appDetailsItem.isBlocked = blocker.hasComponentName(activityInfo.name);
                }
            }
            appDetailsItem.isTracker = ComponentUtils.isTracker(activityInfo.name);
            if (TextUtils.isEmpty(searchQuery)
                    || appDetailsItem.name.toLowerCase(Locale.ROOT).contains(searchQuery))
                appDetailsItems.add(appDetailsItem);
        }
        sortComponents(appDetailsItems);
        receivers.postValue(appDetailsItems);
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> providers = new MutableLiveData<>();

    @UiThread
    private LiveData<List<AppDetailsItem>> getProviders() {
        if (providers.getValue() == null) {
            executor.submit(this::loadProviders);
        }
        return providers;
    }

    @WorkerThread
    @GuardedBy("blockerLocker")
    private void loadProviders() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        if (getPackageInfoInternal() == null || packageInfo.providers == null) {
            // There are no providers
            providers.postValue(appDetailsItems);
            return;
        }
        for (ProviderInfo providerInfo : packageInfo.providers) {
            AppDetailsComponentItem appDetailsItem = new AppDetailsComponentItem(providerInfo);
            appDetailsItem.name = providerInfo.name;
            synchronized (blockerLocker) {
                if (!isExternalApk) {
                    appDetailsItem.isBlocked = blocker.hasComponentName(providerInfo.name);
                }
            }
            appDetailsItem.isTracker = ComponentUtils.isTracker(providerInfo.name);
            if (TextUtils.isEmpty(searchQuery)
                    || appDetailsItem.name.toLowerCase(Locale.ROOT).contains(searchQuery))
                appDetailsItems.add(appDetailsItem);
        }
        sortComponents(appDetailsItems);
        providers.postValue(appDetailsItems);
    }

    @SuppressLint("SwitchIntDef")
    @WorkerThread
    private void sortComponents(List<AppDetailsItem> appDetailsItems) {
        // First sort by name
        Collections.sort(appDetailsItems, (o1, o2) -> o1.name.compareToIgnoreCase(o2.name));
        if (sortOrderComponents == AppDetailsFragment.SORT_BY_NAME) return;
        Collections.sort(appDetailsItems, (o1, o2) -> {
            switch (sortOrderComponents) {
                // No need to sort by name since we've already done it
                case AppDetailsFragment.SORT_BY_BLOCKED:
                    return -Boolean.compare(((AppDetailsComponentItem) o1).isBlocked, ((AppDetailsComponentItem) o2).isBlocked);
                case AppDetailsFragment.SORT_BY_TRACKERS:
                    return -Boolean.compare(((AppDetailsComponentItem) o1).isTracker, ((AppDetailsComponentItem) o2).isTracker);
            }
            return 0;
        });
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> appOps = new MutableLiveData<>();
    private List<AppDetailsItem> appOpItems;

    @UiThread
    private LiveData<List<AppDetailsItem>> getAppOps() {
        if (appOps.getValue() == null) {
            executor.submit(this::loadAppOps);
        }
        return appOps;
    }

    @WorkerThread
    public void setAppOp(AppDetailsItem appDetailsItem) {
        for (int i = 0; i < appOpItems.size(); ++i) {
            if (appOpItems.get(i).name.equals(appDetailsItem.name)) {
                appOpItems.set(i, appDetailsItem);
                break;
            }
        }
    }

    @SuppressLint("SwitchIntDef")
    @WorkerThread
    private void loadAppOps() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        if (packageName == null) {
            appOps.postValue(appDetailsItems);
            return;
        }
        if (!isExternalApk && (AppPref.isRootOrAdbEnabled()
                || PermissionUtils.hasAppOpsPermission(getApplication()))) {
            try {
                int uid = packageInfo.applicationInfo.uid;
                List<OpEntry> opEntries = new ArrayList<>(AppOpsUtils.getChangedAppOps(mAppOpsService, packageName, uid));
                OpEntry opEntry;
                // Include from permissions
                List<String> permissions = getRawPermissions();
                for (String permission : permissions) {
                    int op = AppOpsManager.permissionToOpCode(permission);
                    if (op == OP_NONE || op >= AppOpsManager._NUM_OP) {
                        // Invalid/unsupported app operation
                        continue;
                    }
                    opEntry = new OpEntry(op, mAppOpsService.checkOperation(op, uid, packageName), 0,
                            0, 0, 0, null);
                    if (!opEntries.contains(opEntry)) opEntries.add(opEntry);
                }
                // Include defaults ie. app ops without any associated permissions if requested
                if ((boolean) AppPref.get(AppPref.PrefKey.PREF_APP_OP_SHOW_DEFAULT_BOOL)) {
                    for (int op : AppOpsManager.sOpsWithNoPerm) {
                        if (op >= AppOpsManager._NUM_OP) {
                            // Unsupported app operation
                            continue;
                        }
                        opEntry = new OpEntry(op, AppOpsManager.opToDefaultMode(op), 0,
                                0, 0, 0, null);
                        if (!opEntries.contains(opEntry)) opEntries.add(opEntry);
                    }
                }
                // TODO(24/12/20): App op with MODE_DEFAULT are determined by their associated permissions.
                //  Therefore, mode for such app ops should be determined from the permission.
                Set<String> uniqueSet = new HashSet<>();
                appOpItems = new ArrayList<>(opEntries.size());
                for (OpEntry entry : opEntries) {
                    String opName = AppOpsManager.opToName(entry.getOp());
                    if (uniqueSet.contains(opName)) continue;
                    AppDetailsItem appDetailsItem = new AppDetailsItem(entry);
                    appDetailsItem.name = opName;
                    uniqueSet.add(opName);
                    appOpItems.add(appDetailsItem);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (appOpItems == null) appOpItems = new ArrayList<>(0);
        if (!TextUtils.isEmpty(searchQuery)) {
            for (AppDetailsItem appDetailsItem : appOpItems) {
                if (appDetailsItem.name.toLowerCase(Locale.ROOT).contains(searchQuery)) {
                    appDetailsItems.add(appDetailsItem);
                }
            }
        } else appDetailsItems.addAll(appOpItems);
        Collections.sort(appDetailsItems, (o1, o2) -> {
            switch (sortOrderAppOps) {
                case AppDetailsFragment.SORT_BY_NAME:
                    return o1.name.compareToIgnoreCase(o2.name);
                case AppDetailsFragment.SORT_BY_APP_OP_VALUES:
                    Integer o1Op = ((OpEntry) o1.vanillaItem).getOp();
                    Integer o2Op = ((OpEntry) o2.vanillaItem).getOp();
                    return o1Op.compareTo(o2Op);
                case AppDetailsFragment.SORT_BY_DENIED_APP_OPS:
                    // A slight hack to sort it this way: ignore > foreground > deny > default[ > ask] > allow
                    Integer o1Mode = ((OpEntry) o1.vanillaItem).getMode();
                    Integer o2Mode = ((OpEntry) o2.vanillaItem).getMode();
                    return -o1Mode.compareTo(o2Mode);
            }
            return 0;
        });
        appOps.postValue(appDetailsItems);
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> usesPermissions = new MutableLiveData<>();
    private final ArrayList<AppDetailsPermissionItem> usesPermissionItems = new ArrayList<>();

    @UiThread
    private LiveData<List<AppDetailsItem>> getUsesPermissions() {
        if (usesPermissions.getValue() == null) {
            executor.submit(this::loadUsesPermissions);
        }
        return usesPermissions;
    }

    @WorkerThread
    public void setUsesPermission(AppDetailsPermissionItem appDetailsPermissionItem) {
        AppDetailsPermissionItem permissionItem;
        synchronized (usesPermissionItems) {
            for (int i = 0; i < usesPermissionItems.size(); ++i) {
                permissionItem = usesPermissionItems.get(i);
                if (permissionItem.name.equals(appDetailsPermissionItem.name)) {
                    usesPermissionItems.set(i, appDetailsPermissionItem);
                    break;
                }
            }
        }
    }

    @SuppressLint("SwitchIntDef")
    @WorkerThread
    private void loadUsesPermissions() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        synchronized (usesPermissionItems) {
            usesPermissionItems.clear();
        }
        if (getPackageInfoInternal() == null || packageInfo.requestedPermissions == null) {
            // No requested permissions
            usesPermissions.postValue(appDetailsItems);
            return;
        }
        boolean isRootOrAdbEnabled = AppPref.isRootOrAdbEnabled();
        for (int i = 0; i < packageInfo.requestedPermissions.length; ++i) {
            try {
                PermissionInfo permissionInfo = mPackageManager.getPermissionInfo(packageInfo.requestedPermissions[i],
                        PackageManager.GET_META_DATA);
                AppDetailsPermissionItem appDetailsItem = new AppDetailsPermissionItem(permissionInfo);
                appDetailsItem.name = packageInfo.requestedPermissions[i];
                appDetailsItem.flags = packageInfo.requestedPermissionsFlags[i];
                appDetailsItem.isDangerous = PermissionInfoCompat.getProtection(permissionInfo) == PermissionInfo.PROTECTION_DANGEROUS;
                appDetailsItem.isGranted = (appDetailsItem.flags & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                appDetailsItem.appOp = AppOpsManager.permissionToOpCode(appDetailsItem.name);
                if (isRootOrAdbEnabled) {
                    try {
                        appDetailsItem.permissionFlags = PermissionCompat.getPermissionFlags(appDetailsItem.name,
                                packageName, userHandle);
                        // Check if review required is set (for apps targeting API 23 or less)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (appDetailsItem.permissionFlags
                                & PermissionCompat.FLAG_PERMISSION_REVIEW_REQUIRED) != 0) {
                            // Permission is not granted
                            appDetailsItem.isGranted = false;
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
                if (!isExternalApk && !appDetailsItem.isGranted && appDetailsItem.appOp != OP_NONE) {
                    // Override isGranted only if the original permission isn't granted
                    try {
                        appDetailsItem.isGranted = mAppOpsService.checkOperation(appDetailsItem.appOp,
                                packageInfo.applicationInfo.uid, packageName) == AppOpsManager.MODE_ALLOWED;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                synchronized (usesPermissionItems) {
                    usesPermissionItems.add(appDetailsItem);
                }
            } catch (PackageManager.NameNotFoundException ignore) {
            }
        }
        // Filter items
        synchronized (usesPermissionItems) {
            if (!TextUtils.isEmpty(searchQuery)) {
                for (AppDetailsPermissionItem appDetailsItem : usesPermissionItems)
                    if (appDetailsItem.name.toLowerCase(Locale.ROOT).contains(searchQuery))
                        appDetailsItems.add(appDetailsItem);
            } else appDetailsItems.addAll(usesPermissionItems);
        }
        Collections.sort(appDetailsItems, (o1, o2) -> {
            switch (sortOrderPermissions) {
                case AppDetailsFragment.SORT_BY_NAME:
                    return o1.name.compareToIgnoreCase(o2.name);
                case AppDetailsFragment.SORT_BY_DANGEROUS_PERMS:
                    return -Boolean.compare(((AppDetailsPermissionItem) o1).isDangerous, ((AppDetailsPermissionItem) o2).isDangerous);
                case AppDetailsFragment.SORT_BY_DENIED_PERMS:
                    return Boolean.compare(((AppDetailsPermissionItem) o1).isGranted, ((AppDetailsPermissionItem) o2).isGranted);
            }
            return 0;
        });
        usesPermissions.postValue(appDetailsItems);
    }

    @WorkerThread
    public List<String> getRawPermissions() {
        List<String> rawPermissions = new ArrayList<>();
        if (getPackageInfoInternal() != null && packageInfo.requestedPermissions != null) {
            rawPermissions.addAll(Arrays.asList(packageInfo.requestedPermissions));
        }
        return rawPermissions;
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> permissions = new MutableLiveData<>();

    @UiThread
    private LiveData<List<AppDetailsItem>> getPermissions() {
        if (permissions.getValue() == null) {
            executor.submit(this::loadPermissions);
        }
        return permissions;
    }

    @WorkerThread
    private void loadPermissions() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        if (getPackageInfoInternal() == null || packageInfo.permissions == null) {
            // No custom permissions
            permissions.postValue(appDetailsItems);
            return;
        }
        for (PermissionInfo permissionInfo : packageInfo.permissions) {
            AppDetailsItem appDetailsItem = new AppDetailsItem(permissionInfo);
            appDetailsItem.name = permissionInfo.name;
            if (TextUtils.isEmpty(searchQuery)
                    || appDetailsItem.name.toLowerCase(Locale.ROOT).contains(searchQuery))
                appDetailsItems.add(appDetailsItem);
        }
        Collections.sort(appDetailsItems, (o1, o2) -> o1.name.compareToIgnoreCase(o2.name));
        permissions.postValue(appDetailsItems);
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> features = new MutableLiveData<>();

    @UiThread
    private LiveData<List<AppDetailsItem>> getFeatures() {
        if (features.getValue() == null) {
            executor.submit(this::loadFeatures);
        }
        return features;
    }

    public static final String OPEN_GL_ES = "OpenGL ES";

    @WorkerThread
    private void loadFeatures() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        if (getPackageInfoInternal() == null || packageInfo.reqFeatures == null) {
            // No required features
            features.postValue(appDetailsItems);
            return;
        }
        for (FeatureInfo fi : packageInfo.reqFeatures) {
            if (fi.name == null) fi.name = OPEN_GL_ES;
        }
        Arrays.sort(packageInfo.reqFeatures, (o1, o2) -> o1.name.compareToIgnoreCase(o2.name));
        for (FeatureInfo featureInfo : packageInfo.reqFeatures) {
            AppDetailsItem appDetailsItem = new AppDetailsItem(featureInfo);
            appDetailsItem.name = featureInfo.name;
            appDetailsItems.add(appDetailsItem);
        }
        features.postValue(appDetailsItems);
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> configurations = new MutableLiveData<>();

    @UiThread
    private LiveData<List<AppDetailsItem>> getConfigurations() {
        if (configurations.getValue() == null) {
            executor.submit(this::loadConfigurations);
        }
        return configurations;
    }

    @WorkerThread
    private void loadConfigurations() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        if (getPackageInfoInternal() == null || packageInfo.configPreferences != null) {
            for (ConfigurationInfo configurationInfo : packageInfo.configPreferences) {
                AppDetailsItem appDetailsItem = new AppDetailsItem(configurationInfo);
                appDetailsItems.add(appDetailsItem);
            }
        }
        configurations.postValue(appDetailsItems);
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> signatures = new MutableLiveData<>();
    private ApkVerifier.Result apkVerifierResult;

    @UiThread
    private LiveData<List<AppDetailsItem>> getSignatures() {
        if (signatures.getValue() == null) {
            executor.submit(this::loadSignatures);
        }
        return signatures;
    }

    @AnyThread
    public ApkVerifier.Result getApkVerifierResult() {
        return apkVerifierResult;
    }

    @SuppressWarnings("deprecation")
    @WorkerThread
    private void loadSignatures() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        if (apkFile == null) {
            signatures.postValue(appDetailsItems);
            return;
        }
        try {
            File idsigFile = apkFile.getIdsigFile();
            ApkVerifier.Builder builder = new ApkVerifier.Builder(apkFile.getBaseEntry().getRealCachedFile());
            if (idsigFile != null) {
                builder.setV4SignatureFile(idsigFile);
            }
            ApkVerifier apkVerifier = builder.build();
            apkVerifierResult = apkVerifier.verify();
            // Get signer certificates
            List<X509Certificate> certificates = apkVerifierResult.getSignerCertificates();
            if (certificates != null && certificates.size() > 0) {
                for (X509Certificate certificate : certificates) {
                    AppDetailsItem item = new AppDetailsItem(certificate);
                    item.name = "Signer Certificate";
                    appDetailsItems.add(item);
                }
                if (isExternalApk && packageInfo.signatures == null) {
                    List<Signature> signatures = new ArrayList<>(certificates.size());
                    for (X509Certificate certificate : certificates) {
                        try {
                            signatures.add(new Signature(certificate.getEncoded()));
                        } catch (CertificateEncodingException ignore) {
                        }
                    }
                    packageInfo.signatures = signatures.toArray(new Signature[0]);
                }
            } else {
                //noinspection ConstantConditions Null is deliberately set here to get at least one row
                appDetailsItems.add(new AppDetailsItem(null));
            }
            // Get source stamp certificate
            if (apkVerifierResult.isSourceStampVerified()) {
                ApkVerifier.Result.SourceStampInfo sourceStampInfo = apkVerifierResult.getSourceStampInfo();
                X509Certificate certificate = sourceStampInfo.getCertificate();
                if (certificate != null) {
                    AppDetailsItem item = new AppDetailsItem(certificate);
                    item.name = "SourceStamp Certificate";
                    appDetailsItems.add(item);
                }
            }
        } catch (IOException | ApkFormatException | NoSuchAlgorithmException | RemoteException e) {
            e.printStackTrace();
        }
        signatures.postValue(appDetailsItems);
    }

    @NonNull
    private final MutableLiveData<List<AppDetailsItem>> sharedLibraries = new MutableLiveData<>();

    @UiThread
    private LiveData<List<AppDetailsItem>> getSharedLibraries() {
        if (sharedLibraries.getValue() == null) {
            executor.submit(this::loadSharedLibraries);
        }
        return sharedLibraries;
    }

    @WorkerThread
    private void loadSharedLibraries() {
        List<AppDetailsItem> appDetailsItems = new ArrayList<>();
        if (getPackageInfoInternal() == null) {
            sharedLibraries.postValue(appDetailsItems);
            return;
        }
        ApplicationInfo info = packageInfo.applicationInfo;
        File jniDir = new File(info.nativeLibraryDir);
        if (info.sharedLibraryFiles != null) {
            for (String sharedLibrary : info.sharedLibraryFiles) {
                File sharedLib = new File(sharedLibrary);
                AppDetailsItem appDetailsItem = new AppDetailsItem(sharedLib);
                appDetailsItem.name = sharedLib.getName();
                appDetailsItems.add(appDetailsItem);
            }
        }
        if (jniDir.isDirectory()) {
            File[] libs = jniDir.listFiles();
            if (libs != null) {
                for (File lib : libs) {
                    AppDetailsItem appDetailsItem = new AppDetailsItem(lib);
                    appDetailsItem.name = lib.getName();
                    appDetailsItems.add(appDetailsItem);
                }
            }
        }
        sharedLibraries.postValue(appDetailsItems);
    }

    /**
     * Helper class to look for interesting changes to the installed apps
     * so that the loader can be updated.
     */
    public static class PackageIntentReceiver extends PackageChangeReceiver {
        final AppDetailsViewModel model;

        public PackageIntentReceiver(@NonNull AppDetailsViewModel model) {
            super(model.getApplication());
            this.model = model;
        }

        @Override
        @WorkerThread
        protected void onPackageChanged(Intent intent, @Nullable Integer uid, @Nullable String[] packages) {
            if (uid != null && (model.packageInfo == null || model.packageInfo.applicationInfo.uid == uid)) {
                Log.d("ADVM", "Package is changed.");
                model.setIsPackageChanged();
            } else if (packages != null) {
                for (String packageName : packages) {
                    if (packageName.equals(model.packageName)) {
                        Log.d("ADVM", "Package availability changed.");
                        model.setIsPackageChanged();
                    }
                }
            } else {
                Log.d("ADVM", "Locale changed.");
                model.setIsPackageChanged();
            }
        }
    }
}
