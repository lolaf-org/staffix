/*
 * Copyright © 2024-2026 Lolaf.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lolaf.staffix.stores.sessions.file;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ObjectMerger class.
 * Tests various scenarios including null handling, nested objects, and edge cases.
 */
class TestObjectMerger {

    private User defaultUser;

    @BeforeEach
    void setUp() {
        Address defaultAddress = new Address("123 Default St", "Default City", "00000", "USA");
        ContactInfo defaultContact = new ContactInfo("default@example.com", "000-000-0000");
        defaultUser = new User("Default User", 25, "user", defaultAddress, defaultContact, true);
    }

    @Test
    @DisplayName("Should merge null primitive wrapper fields from default to target")
    void testMergePrimitiveWrappers() throws IllegalAccessException {
        User target = new User("John", null, null, null, null, null);

        ObjectMerger.merge(defaultUser, target);

        assertEquals("John", target.getName()); // Kept from target
        assertEquals(25, target.getAge()); // From default
        assertEquals("user", target.getRole()); // From default
        assertTrue(target.getActive()); // From default
    }

    @Test
    @DisplayName("Should not override non-null target fields")
    void testDoesNotOverrideNonNullFields() throws IllegalAccessException {
        User target = new User("Jane", 30, "admin", null, null, false);

        ObjectMerger.merge(defaultUser, target);

        assertEquals("Jane", target.getName());
        assertEquals(30, target.getAge());
        assertEquals("admin", target.getRole());
        assertFalse(target.getActive());
    }

    @Test
    @DisplayName("Should merge nested objects when target nested object is null")
    void testMergeNullNestedObject() throws IllegalAccessException {
        User target = new User("John", 30, "admin", null, null, null);

        ObjectMerger.merge(defaultUser, target);

        assertNotNull(target.getAddress());
        assertEquals("123 Default St", target.getAddress().getStreet());
        assertEquals("Default City", target.getAddress().getCity());
        assertEquals("00000", target.getAddress().getZipCode());
        assertEquals("USA", target.getAddress().getCountry());
    }

    @Test
    @DisplayName("Should recursively merge nested objects when both are non-null")
    void testRecursiveMergeNestedObjects() throws IllegalAccessException {
        Address targetAddress = new Address("456 Main St", null, "99999", null);
        User target = new User("John", 30, null, targetAddress, null, null);

        ObjectMerger.merge(defaultUser, target);

        // Target values should be kept
        assertEquals("456 Main St", target.getAddress().getStreet());
        assertEquals("99999", target.getAddress().getZipCode());

        // Default values should fill nulls
        assertEquals("Default City", target.getAddress().getCity());
        assertEquals("USA", target.getAddress().getCountry());
    }

    @Test
    @DisplayName("Should handle all null target fields")
    void testAllNullTargetFields() throws IllegalAccessException {
        User target = new User(null, null, null, null, null, null);

        ObjectMerger.merge(defaultUser, target);

        assertEquals("Default User", target.getName());
        assertEquals(25, target.getAge());
        assertEquals("user", target.getRole());
        assertNotNull(target.getAddress());
        assertNotNull(target.getContactInfo());
        assertTrue(target.getActive());
    }

    @Test
    @DisplayName("Should handle all non-null target fields (no changes)")
    void testAllNonNullTargetFields() throws IllegalAccessException {
        Address targetAddress = new Address("789 Custom Ave", "Custom City", "11111", "Canada");
        ContactInfo targetContact = new ContactInfo("john@custom.com", "999-999-9999");
        User target = new User("Jane", 35, "superadmin", targetAddress, targetContact, false);

        ObjectMerger.merge(defaultUser, target);

        // All target values should remain unchanged
        assertEquals("Jane", target.getName());
        assertEquals(35, target.getAge());
        assertEquals("superadmin", target.getRole());
        assertEquals("789 Custom Ave", target.getAddress().getStreet());
        assertEquals("Custom City", target.getAddress().getCity());
        assertEquals("john@custom.com", target.getContactInfo().getEmail());
        assertFalse(target.getActive());
    }

    @Test
    @DisplayName("Should merge inherited fields from parent class")
    void testMergeInheritedFields() throws IllegalAccessException {
        Child defaultChild = new Child();
        defaultChild.setParentField("Parent Value");
        defaultChild.setChildField("Child Value");

        Child targetChild = new Child();
        targetChild.setParentField(null);
        targetChild.setChildField(null);

        ObjectMerger.merge(defaultChild, targetChild);

        assertEquals("Parent Value", targetChild.getParentField());
        assertEquals("Child Value", targetChild.getChildField());
    }

    @Test
    @DisplayName("Should handle deeply nested objects")
    void testDeeplyNestedObjects() throws IllegalAccessException {
        ContactInfo targetContact = new ContactInfo(null, "555-5555");
        Address targetAddress = new Address("999 Test St", null, null, null);
        User target = new User("Bob", null, null, targetAddress, targetContact, null);

        ObjectMerger.merge(defaultUser, target);

        // Check nested ContactInfo
        assertEquals("default@example.com", target.getContactInfo().getEmail());
        assertEquals("555-5555", target.getContactInfo().getPhone());

        // Check nested Address
        assertEquals("999 Test St", target.getAddress().getStreet());
        assertEquals("Default City", target.getAddress().getCity());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when default instance is null")
    void testNullDefaultInstance() {
        User target = new User("John", null, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> {
            ObjectMerger.merge(null, target);
        });
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when target instance is null")
    void testNullTargetInstance() {
        assertThrows(IllegalArgumentException.class, () -> {
            ObjectMerger.merge(defaultUser, null);
        });
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when instances are different classes")
    void testDifferentClasses() {
        Address address = new Address("123 St", "City", "12345", "USA");

        assertThrows(IllegalArgumentException.class, () -> {
            ObjectMerger.merge(defaultUser, address);
        });
    }

    @Test
    @DisplayName("Should handle String fields correctly")
    void testStringFields() throws IllegalAccessException {
        User target = new User(null, 30, "admin", null, null, null);

        ObjectMerger.merge(defaultUser, target);

        assertEquals("Default User", target.getName());
        assertEquals(30, target.getAge());
        assertEquals("admin", target.getRole());
    }

    @Test
    @DisplayName("Should handle Boolean wrapper correctly")
    void testBooleanWrapper() throws IllegalAccessException {
        User target1 = new User("John", null, null, null, null, null);
        ObjectMerger.merge(defaultUser, target1);
        assertTrue(target1.getActive());

        User target2 = new User("Jane", null, null, null, null, false);
        ObjectMerger.merge(defaultUser, target2);
        assertFalse(target2.getActive()); // Should keep false, not override
    }

    @Test
    @DisplayName("Should handle multiple nested objects at different levels")
    void testMultipleNestedLevels() throws IllegalAccessException {
        Address partialAddress = new Address("100 Partial St", null, "22222", null);
        ContactInfo partialContact = new ContactInfo("partial@test.com", null);
        User target = new User(null, 40, null, partialAddress, partialContact, null);

        ObjectMerger.merge(defaultUser, target);

        // Root level
        assertEquals("Default User", target.getName());
        assertEquals(40, target.getAge());
        assertEquals("user", target.getRole());
        assertTrue(target.getActive());

        // First nested level - Address
        assertEquals("100 Partial St", target.getAddress().getStreet());
        assertEquals("Default City", target.getAddress().getCity());
        assertEquals("22222", target.getAddress().getZipCode());
        assertEquals("USA", target.getAddress().getCountry());

        // First nested level - ContactInfo
        assertEquals("partial@test.com", target.getContactInfo().getEmail());
        assertEquals("000-000-0000", target.getContactInfo().getPhone());
    }

    @Data
    @AllArgsConstructor
    static class Address {
        private String street;
        private String city;
        private String zipCode;
        private String country;
    }

    @Data
    @AllArgsConstructor
    static class ContactInfo {
        private String email;
        private String phone;
    }

    @Data
    @AllArgsConstructor
    static class User {
        private String name;
        private Integer age;
        private String role;
        private Address address;
        private ContactInfo contactInfo;
        private Boolean active;
    }

    @Data
    static class Parent {
        protected String parentField;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    static class Child extends Parent {
        private String childField;
    }
}