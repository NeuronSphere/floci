package io.github.hectorvent.floci.services.eks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksHostVolumesTest {

    @Test
    void parsePrefixedHostVolume() {
        Map<String, String> tags = Map.of("floci:host-volume:workspace", "/Users/dev/projects:/workspace");
        List<String> specs = EksHostVolumes.parse(tags);
        assertEquals(List.of("/Users/dev/projects:/workspace"), specs);
    }

    @Test
    void parseReadOnlyHostVolume() {
        Map<String, String> tags = Map.of("floci:host-volume:data", "/srv/data:/data:ro");
        List<String> specs = EksHostVolumes.parse(tags);
        assertEquals(List.of("/srv/data:/data:ro"), specs);
        EksHostVolumes.HostVolume volume = EksHostVolumes.toVolume(specs.get(0));
        assertEquals("/srv/data", volume.hostPath());
        assertEquals("/data", volume.containerPath());
        assertTrue(volume.readOnly());
    }

    @Test
    void parseExplicitReadWriteNormalizesToDefault() {
        Map<String, String> tags = Map.of("floci:host-volume:data", "/srv/data:/data:rw");
        assertEquals(List.of("/srv/data:/data"), EksHostVolumes.parse(tags));
        assertFalse(EksHostVolumes.toVolume("/srv/data:/data").readOnly());
    }

    @Test
    void parsePluralJsonList() {
        Map<String, String> tags = Map.of("floci:host-volumes", "[\"/a:/mnt/a\", \"/b:/mnt/b:ro\"]");
        List<String> specs = EksHostVolumes.parse(tags);
        assertEquals(List.of("/a:/mnt/a", "/b:/mnt/b:ro"), specs);
    }

    @Test
    void parseIsSortedByContainerPathRegardlessOfTagOrder() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("floci:host-volume:z", "/z:/mnt/z");
        tags.put("floci:host-volume:a", "/a:/mnt/a");
        assertEquals(List.of("/a:/mnt/a", "/z:/mnt/z"), EksHostVolumes.parse(tags));
    }

    @Test
    void parseIgnoresUnrelatedTags() {
        Map<String, String> tags = Map.of(
                "env", "dev",
                "floci:kubelet-arg:max-pods=250", "true");
        assertTrue(EksHostVolumes.parse(tags).isEmpty());
        assertTrue(EksHostVolumes.parse(null).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/only-one-path",
            "/a:/b:rx",
            "/a:/b:ro:extra",
            ":/b",
            "/a:",
            "/a:relative/path",
            "/a:/mnt/../etc",
            "/a:/",
            "/a:/var/lib/rancher/k3s",
            "/a:/var/lib/rancher/k3s/server",
            "/a:/var/lib",
            "/a:/mnt/a\n"
    })
    void parseRejectsInvalidSpecs(String spec) {
        Map<String, String> tags = Map.of("floci:host-volume:bad", spec);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EksHostVolumes.parse(tags));
        assertTrue(ex.getMessage().contains("floci:host-volume:bad"), ex.getMessage());
    }

    @Test
    void parseRejectsDuplicateContainerPaths() {
        Map<String, String> tags = Map.of(
                "floci:host-volume:one", "/a:/mnt/shared",
                "floci:host-volume:two", "/b:/mnt/shared");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EksHostVolumes.parse(tags));
        assertTrue(ex.getMessage().contains("/mnt/shared"), ex.getMessage());
    }

    @Test
    void parseRejectsMalformedPluralJson() {
        Map<String, String> tags = Map.of("floci:host-volumes", "not-json");
        assertThrows(IllegalArgumentException.class, () -> EksHostVolumes.parse(tags));
    }
}
