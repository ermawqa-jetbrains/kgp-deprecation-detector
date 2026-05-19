package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertEquals

class DeprecatedSymbolTest {

    @Test
    fun searchName_uses_simple_class_name_when_no_member() {
        val s = DeprecatedSymbol(
            className = "org.jetbrains.kotlin.gradle.plugin.OldClass",
            memberName = null,
            memberDescriptor = null,
            level = DeprecationLevel.ERROR,
            message = "removed",
            replaceWith = null
        )
        assertEquals("OldClass", s.searchName)
    }

    @Test
    fun searchName_strips_get_prefix_and_lowercases_first_char() {
        val s = symbol(member = "getDefaultSourceSetName")
        assertEquals("defaultSourceSetName", s.searchName)
    }

    @Test
    fun searchName_strips_set_prefix() {
        val s = symbol(member = "setDefaultSourceSetName")
        assertEquals("defaultSourceSetName", s.searchName)
    }

    @Test
    fun searchName_keeps_is_prefix() {
        val s = symbol(member = "isEnabled")
        assertEquals("isEnabled", s.searchName)
    }

    @Test
    fun searchName_returns_member_when_no_accessor_prefix() {
        val s = symbol(member = "applyDefaultHierarchyTemplate")
        assertEquals("applyDefaultHierarchyTemplate", s.searchName)
    }

    @Test
    fun searchName_does_not_strip_get_when_followed_by_lowercase() {
        val s = symbol(member = "getter")
        assertEquals("getter", s.searchName)
    }

    @Test
    fun qualifiedName_class_only() {
        val s = DeprecatedSymbol("a.b.Foo", null, null, DeprecationLevel.ERROR, "", null)
        assertEquals("a.b.Foo", s.qualifiedName)
    }

    @Test
    fun qualifiedName_with_member() {
        val s = symbol(member = "bar")
        assertEquals("test.Foo.bar", s.qualifiedName)
    }

    private fun symbol(member: String): DeprecatedSymbol = DeprecatedSymbol(
        className = "test.Foo",
        memberName = member,
        memberDescriptor = "()V",
        level = DeprecationLevel.WARNING,
        message = "",
        replaceWith = null
    )
}
