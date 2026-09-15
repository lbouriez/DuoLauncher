package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconPackIndexTest {
    @Test fun parsesComponentAndPackageFallbacks() {
        val index = IconPackIndex.parse("""<resources><item component="ComponentInfo{com.example/com.example.Main}" drawable="example_main" /></resources>""")
        assertEquals("example_main", index.drawableFor("com.example/com.example.Main"))
        assertEquals("example_main", index.drawableFor("com.example/com.example.Other"))
    }

    @Test fun rejectsMalformedOrUnsafeDrawableNames() {
        val index = IconPackIndex.parse("""<item component="com.bad/com.bad.Main" drawable="../not-a-resource"/><item drawable="missing_component"/>""")
        assertNull(index.drawableFor("com.bad/com.bad.Main"))
    }
}
