/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.patches.all.misc.fix.spoofsignature

import com.android.tools.smali.dexlib2.analysis.reflection.util.ReflectionUtils

object Constants {
    const val SPOOF_CLASS_JAVA_NAME = "app.morphe.extension.signature.SignatureSpoof"
    val SPOOF_CLASS_SMALI_NAME: String = ReflectionUtils.javaToDexName(SPOOF_CLASS_JAVA_NAME)
}