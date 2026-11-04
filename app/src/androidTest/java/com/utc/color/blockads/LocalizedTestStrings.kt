package com.utc.color.blockads

import androidx.test.platform.app.InstrumentationRegistry

internal fun appString(resource: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *args)
