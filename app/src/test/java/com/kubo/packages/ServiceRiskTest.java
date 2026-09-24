package com.kubo.packages;

import com.kubo.model.packages.ServiceRisk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Telling apart the services that can be restarted and the two or three that cannot, casually. */
class ServiceRiskTest {

    @Test
    void theWayInIsNotRestartedWithoutAsking() {
        // Kubo is connected through this. A list of identical buttons invited somebody to press it.
        for (String unit : new String[]{"ssh.service", "sshd.service", "networking.service",
                "systemd-networkd.service", "NetworkManager.service", "systemd-resolved.service"}) {
            assertEquals(ServiceRisk.CUTS_THE_WAY_IN, ServiceRisk.of(unit), unit);
            assertFalse(ServiceRisk.safeToBatch(unit), unit);
        }
        assertTrue(ServiceRisk.CUTS_THE_WAY_IN.warning().contains("provider's console"));
    }

    @Test
    void whatTheMachineLeansOnIsNotRestartedWithoutAskingEither() {
        for (String unit : new String[]{"dbus.service", "systemd-logind.service", "systemd-journald.service",
                "polkit.service", "systemd-udevd.service"}) {
            assertEquals(ServiceRisk.SHAKES_THE_MACHINE, ServiceRisk.of(unit), unit);
        }
    }

    @Test
    void everythingElseIsOrdinaryAndGoesInTheBatch() {
        for (String unit : new String[]{"pm2-xana.service", "fail2ban.service", "nginx.service",
                "postfix@-.service", "cron.service", "docker.service", "redis-server.service"}) {
            assertEquals(ServiceRisk.ORDINARY, ServiceRisk.of(unit), unit);
            assertTrue(ServiceRisk.safeToBatch(unit), unit);
        }
        assertEquals("", ServiceRisk.ORDINARY.badge());
        assertFalse(ServiceRisk.ORDINARY.asks());
    }

    @Test
    void aTemplateInstanceIsJudgedByWhatItIsAnInstanceOf() {
        // "user@1000.service" and "getty@tty1.service" are instances; the part before the @ is the thing.
        assertEquals(ServiceRisk.SHAKES_THE_MACHINE, ServiceRisk.of("user@1000.service"));
        assertEquals(ServiceRisk.SHAKES_THE_MACHINE, ServiceRisk.of("getty@tty1.service"));
        // postfix is an ordinary instance and must not be dragged in by the same rule.
        assertEquals(ServiceRisk.ORDINARY, ServiceRisk.of("postfix@-.service"));
    }

    @Test
    void theEndingOnTheNameDoesNotChangeTheAnswer() {
        assertEquals(ServiceRisk.CUTS_THE_WAY_IN, ServiceRisk.of("ssh"));
        assertEquals(ServiceRisk.CUTS_THE_WAY_IN, ServiceRisk.of("ssh.socket"));
        assertEquals(ServiceRisk.CUTS_THE_WAY_IN, ServiceRisk.of("SSH.SERVICE"));
        assertEquals(ServiceRisk.ORDINARY, ServiceRisk.of(""));
        assertEquals(ServiceRisk.ORDINARY, ServiceRisk.of(null));
    }

    @Test
    void everyRiskThatAsksHasSomethingToSay() {
        for (ServiceRisk risk : ServiceRisk.values()) {
            assertEquals(risk != ServiceRisk.ORDINARY, risk.asks(), risk.name());
            assertEquals(risk.asks(), !risk.badge().isBlank(), risk.name());
            assertEquals(risk.asks(), !risk.warning().isBlank(), risk.name());
        }
    }

    @Test
    void theRebootCommandWorksWithAndWithoutSystemd() {
        assertTrue(AptCommands.REBOOT.contains("systemctl reboot"));
        assertTrue(AptCommands.REBOOT.contains("shutdown -r"), "not every machine has systemd");
    }
}
