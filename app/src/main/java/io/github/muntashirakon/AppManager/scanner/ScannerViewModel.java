// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.scanner;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Pair;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkFormatException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import io.github.muntashirakon.AppManager.utils.DigestUtils;
import io.github.muntashirakon.AppManager.utils.FileUtils;
import io.github.muntashirakon.AppManager.utils.MultithreadedExecutor;
import io.github.muntashirakon.io.VirtualFileSystem;

public class ScannerViewModel extends AndroidViewModel {
    private File apkFile;
    private boolean cached;
    private Uri apkUri;
    private int dexVfsId;
    private List<String> classListAll;
    private List<String> trackerClassList = new ArrayList<>();
    private List<String> libClassList = new ArrayList<>();

    private CountDownLatch waitForFile;
    private final MultithreadedExecutor executor = MultithreadedExecutor.getNewInstance();
    private final MutableLiveData<Pair<String, String>[]> apkChecksums = new MutableLiveData<>();
    private final MutableLiveData<ApkVerifier.Result> apkVerifierResult = new MutableLiveData<>();
    private final MutableLiveData<PackageInfo> packageInfo = new MutableLiveData<>();
    private final MutableLiveData<List<String>> allClasses = new MutableLiveData<>();

    public ScannerViewModel(@NonNull Application application) {
        super(application);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdownNow();
        if (cached && apkFile != null) {
            // Only attempt to delete the apk file if it's cached
            FileUtils.deleteSilently(apkFile);
        }
        try {
            VirtualFileSystem.unmount(dexVfsId);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @AnyThread
    public void loadSummary(@Nullable File apkFile, @NonNull Uri apkUri) {
        cached = false;
        this.apkFile = apkFile;
        this.apkUri = apkUri;
        waitForFile = new CountDownLatch(1);
        // Cache files
        executor.submit(() -> {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            try {
                cacheFileIfRequired();
            } finally {
                waitForFile.countDown();
            }
        });
        // Generate APK checksums
        executor.submit(this::generateApkChecksums);
        // Verify APK
        executor.submit(this::loadApkVerifierResult);
        // Load package info
        executor.submit(this::loadPackageInfo);
        // Load all classes
        executor.submit(() -> {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            loadAllClasses();
        });
    }

    public LiveData<Pair<String, String>[]> getApkChecksums() {
        return apkChecksums;
    }

    public LiveData<ApkVerifier.Result> getApkVerifierResult() {
        return apkVerifierResult;
    }

    public LiveData<PackageInfo> getPackageInfo() {
        return packageInfo;
    }

    public LiveData<List<String>> getAllClasses() {
        return allClasses;
    }

    public File getApkFile() {
        return apkFile;
    }

    public List<String> getClassListAll() {
        return classListAll;
    }

    public int getDexVfsId() {
        return dexVfsId;
    }

    @WorkerThread
    private void cacheFileIfRequired() {
        // Test if this path is readable
        if (this.apkFile == null || !apkFile.canRead()) {
            // Not readable, cache the file
            try (InputStream uriStream = getApplication().getContentResolver().openInputStream(apkUri)) {
                apkFile = FileUtils.getCachedFile(uriStream);
                cached = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @WorkerThread
    private void generateApkChecksums() {
        waitForFile();
        apkChecksums.postValue(DigestUtils.getDigests(apkFile));
    }

    private void loadApkVerifierResult() {
        waitForFile();
        try {
            // TODO: 26/5/21 Add v4 verification
            ApkVerifier.Builder builder = new ApkVerifier.Builder(apkFile);
            ApkVerifier apkVerifier = builder.build();
            ApkVerifier.Result apkVerifierResult = apkVerifier.verify();
            this.apkVerifierResult.postValue(apkVerifierResult);
        } catch (IOException | ApkFormatException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    @WorkerThread
    private void loadPackageInfo() {
        waitForFile();
        final PackageManager pm = getApplication().getPackageManager();
        packageInfo.postValue(pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0));
    }

    @WorkerThread
    private void loadAllClasses() {
        waitForFile();
        try {
            VirtualFileSystem.DexFileSystem dfs = new VirtualFileSystem.DexFileSystem(Uri.fromFile(apkFile), apkFile);
            dexVfsId = VirtualFileSystem.mount(dfs);
            classListAll = dfs.getDexClasses().getClassNames();
        } catch (Throwable e) {
            classListAll = Collections.emptyList();
        }
        allClasses.postValue(classListAll);
    }

    @WorkerThread
    public void waitForFile() {
        try {
            waitForFile.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
