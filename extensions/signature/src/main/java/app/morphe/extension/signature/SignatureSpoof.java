/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.extension.signature;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.List;

@SuppressWarnings("unused")
public class SignatureSpoof {
    static String PACKAGE_NAME = "PACKAGE_NAME_PLACEHOLDER";
    static String SIGNATURE = "SIGNATURE_PLACEHOLDER";
    static Signature spoofedSignature = new Signature(Base64.decode(SIGNATURE, Base64.DEFAULT));

    /**
     * Manifest metadata the patch adds to every app it patches, holding the original signing
     * certificate of that app. Must match ORIGINAL_SIGNATURE_METADATA of the patch.
     */
    private static final String ORIGINAL_SIGNATURE_METADATA = "app.morphe.ORIGINAL_SIGNATURE";

    private static final String LOG_TAG = "morphe";

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public static PackageInfo getPackageInfo(PackageManager pm, String packageName, PackageManager.PackageInfoFlags flags) throws PackageManager.NameNotFoundException {
        return spoofSignature(pm, pm.getPackageInfo(packageName, flags));
    }

    public static PackageInfo getPackageInfo(PackageManager pm, String packageName, int flags) throws PackageManager.NameNotFoundException {
        return spoofSignature(pm, pm.getPackageInfo(packageName, flags));
    }

    @androidx.annotation.RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public static List<PackageInfo> getInstalledPackages(PackageManager pm, PackageManager.PackageInfoFlags flags) {
        return spoofSignatures(pm, pm.getInstalledPackages(flags));
    }

    public static List<PackageInfo> getInstalledPackages(PackageManager pm, int flags) {
        return spoofSignatures(pm, pm.getInstalledPackages(flags));
    }

    private static List<PackageInfo> spoofSignatures(PackageManager pm, List<PackageInfo> packages) {
        for (PackageInfo info : packages) {
            spoofSignature(pm, info);
        }
        return packages;
    }

    private static PackageInfo spoofSignature(PackageManager pm, PackageInfo info) {
        if (info.packageName.equals(PACKAGE_NAME)) {
            replaceSigner(info, spoofedSignature);
        } else if (hasSigners(info)) {
            Signature original = originalSignatureOfSameKeyApp(pm, info);
            if (original != null) {
                replaceSigner(info, original);
            }
        }

        return info;
    }

    /**
     * The original certificate of another app that was also patched with this patch and is signed
     * with the same key as this app, or null for any other app. Apps of the same developer often
     * trust each other by their original certificates, and after re-signing that only works if both
     * report them. Only the user can sign an app with the key of this app, so the certificate an
     * app declares in its manifest is only trusted if it shares that key.
     */
    @Nullable
    private static Signature originalSignatureOfSameKeyApp(PackageManager pm, PackageInfo info) {
        ApplicationInfo appInfo = info.applicationInfo;
        if (appInfo == null || pm.checkSignatures(Process.myUid(), appInfo.uid) != PackageManager.SIGNATURE_MATCH) {
            return null;
        }

        Bundle metaData;
        try {
            metaData = pm.getApplicationInfo(info.packageName, PackageManager.GET_META_DATA).metaData;
        } catch (PackageManager.NameNotFoundException ex) {
            // The app was uninstalled since its package info was read.
            Log.w(LOG_TAG, "Could not read meta-data of " + info.packageName, ex);
            return null;
        }
        String signature = metaData == null ? null : metaData.getString(ORIGINAL_SIGNATURE_METADATA);
        return signature == null ? null : new Signature(Base64.decode(signature, Base64.DEFAULT));
    }

    @SuppressWarnings("deprecation")
    private static boolean hasSigners(PackageInfo info) {
        return info.signatures != null
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null);
    }

    /**
     * Replaces the signer in place. For an app with one signer and no past certificates,
     * SigningInfo returns the same array for getApkContentsSigners() and
     * getSigningCertificateHistory(), so this changes both.
     */
    @SuppressWarnings("deprecation")
    private static void replaceSigner(PackageInfo info, Signature signature) {
        if (info.signatures != null && info.signatures.length > 0) {
            info.signatures[0] = signature;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo != null) {
                Signature[] signaturesArray = info.signingInfo.getApkContentsSigners();
                if (signaturesArray != null && signaturesArray.length > 0) {
                    signaturesArray[0] = signature;
                }
            }
        }
    }
}
