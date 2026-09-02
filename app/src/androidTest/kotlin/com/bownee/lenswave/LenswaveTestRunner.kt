package com.bownee.lenswave

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class LenswaveTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        LenswaveApplication.disableAccountSessionStartupForTests()
        return super.newApplication(classLoader, className, context)
    }
}
