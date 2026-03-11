package com.sslproxy.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for admin-related {@link AuditEventType} enum values.
 * Verifies that all admin audit event types exist, have correct codes,
 * and report the correct security classification.
 */
class AuditEventTypeAdminTest {

    // ===== Event existence and code verification =====

    @Test
    void should_haveCorrectCode_when_adminBlacklistAdd() {
        assertThat(AuditEventType.ADMIN_BLACKLIST_ADD.getCode())
                .isEqualTo("ADMIN_BLACKLIST_ADD");
    }

    @Test
    void should_haveCorrectCode_when_adminBlacklistRemove() {
        assertThat(AuditEventType.ADMIN_BLACKLIST_REMOVE.getCode())
                .isEqualTo("ADMIN_BLACKLIST_REMOVE");
    }

    @Test
    void should_haveCorrectCode_when_adminWhitelistAdd() {
        assertThat(AuditEventType.ADMIN_WHITELIST_ADD.getCode())
                .isEqualTo("ADMIN_WHITELIST_ADD");
    }

    @Test
    void should_haveCorrectCode_when_adminWhitelistRemove() {
        assertThat(AuditEventType.ADMIN_WHITELIST_REMOVE.getCode())
                .isEqualTo("ADMIN_WHITELIST_REMOVE");
    }

    @Test
    void should_haveCorrectCode_when_adminReload() {
        assertThat(AuditEventType.ADMIN_RELOAD.getCode())
                .isEqualTo("ADMIN_RELOAD");
    }

    @Test
    void should_haveCorrectCode_when_adminDrainStart() {
        assertThat(AuditEventType.ADMIN_DRAIN_START.getCode())
                .isEqualTo("ADMIN_DRAIN_START");
    }

    @Test
    void should_haveCorrectCode_when_adminDrainCancel() {
        assertThat(AuditEventType.ADMIN_DRAIN_CANCEL.getCode())
                .isEqualTo("ADMIN_DRAIN_CANCEL");
    }

    @Test
    void should_haveCorrectCode_when_adminUnauthorized() {
        assertThat(AuditEventType.ADMIN_UNAUTHORIZED.getCode())
                .isEqualTo("ADMIN_UNAUTHORIZED");
    }

    // ===== Category verification =====

    @Test
    void should_haveSecurityCategory_when_adminBlacklistAdd() {
        assertThat(AuditEventType.ADMIN_BLACKLIST_ADD.getCategory())
                .isEqualTo("security");
    }

    @Test
    void should_haveSecurityCategory_when_adminBlacklistRemove() {
        assertThat(AuditEventType.ADMIN_BLACKLIST_REMOVE.getCategory())
                .isEqualTo("security");
    }

    @Test
    void should_haveSecurityCategory_when_adminWhitelistAdd() {
        assertThat(AuditEventType.ADMIN_WHITELIST_ADD.getCategory())
                .isEqualTo("security");
    }

    @Test
    void should_haveSecurityCategory_when_adminWhitelistRemove() {
        assertThat(AuditEventType.ADMIN_WHITELIST_REMOVE.getCategory())
                .isEqualTo("security");
    }

    @Test
    void should_haveInfoCategory_when_adminReload() {
        assertThat(AuditEventType.ADMIN_RELOAD.getCategory())
                .isEqualTo("info");
    }

    @Test
    void should_haveWarningCategory_when_adminDrainStart() {
        assertThat(AuditEventType.ADMIN_DRAIN_START.getCategory())
                .isEqualTo("warning");
    }

    @Test
    void should_haveInfoCategory_when_adminDrainCancel() {
        assertThat(AuditEventType.ADMIN_DRAIN_CANCEL.getCategory())
                .isEqualTo("info");
    }

    @Test
    void should_haveSecurityCategory_when_adminUnauthorized() {
        assertThat(AuditEventType.ADMIN_UNAUTHORIZED.getCategory())
                .isEqualTo("security");
    }

    // ===== isSecurityEvent() verification =====

    @Test
    void should_returnTrue_when_adminBlacklistAddIsSecurityEvent() {
        assertThat(AuditEventType.ADMIN_BLACKLIST_ADD.isSecurityEvent()).isTrue();
    }

    @Test
    void should_returnTrue_when_adminBlacklistRemoveIsSecurityEvent() {
        assertThat(AuditEventType.ADMIN_BLACKLIST_REMOVE.isSecurityEvent()).isTrue();
    }

    @Test
    void should_returnTrue_when_adminWhitelistAddIsSecurityEvent() {
        assertThat(AuditEventType.ADMIN_WHITELIST_ADD.isSecurityEvent()).isTrue();
    }

    @Test
    void should_returnTrue_when_adminWhitelistRemoveIsSecurityEvent() {
        assertThat(AuditEventType.ADMIN_WHITELIST_REMOVE.isSecurityEvent()).isTrue();
    }

    @Test
    void should_returnTrue_when_adminUnauthorizedIsSecurityEvent() {
        assertThat(AuditEventType.ADMIN_UNAUTHORIZED.isSecurityEvent()).isTrue();
    }

    @Test
    void should_returnFalse_when_adminReloadIsSecurityEvent() {
        assertThat(AuditEventType.ADMIN_RELOAD.isSecurityEvent()).isFalse();
    }

    @Test
    void should_returnFalse_when_adminDrainCancelIsSecurityEvent() {
        assertThat(AuditEventType.ADMIN_DRAIN_CANCEL.isSecurityEvent()).isFalse();
    }

    @Test
    void should_returnFalse_when_adminDrainStartIsSecurityEvent() {
        assertThat(AuditEventType.ADMIN_DRAIN_START.isSecurityEvent()).isFalse();
    }

    // ===== Description non-null check =====

    @ParameterizedTest
    @EnumSource(value = AuditEventType.class, names = {
            "ADMIN_BLACKLIST_ADD",
            "ADMIN_BLACKLIST_REMOVE",
            "ADMIN_WHITELIST_ADD",
            "ADMIN_WHITELIST_REMOVE",
            "ADMIN_RELOAD",
            "ADMIN_DRAIN_START",
            "ADMIN_DRAIN_CANCEL",
            "ADMIN_UNAUTHORIZED"
    })
    void should_haveNonBlankDescription_when_adminEventType(AuditEventType eventType) {
        assertThat(eventType.getDescription()).isNotBlank();
    }
}
