package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * {@code floci.services.eks.host-volume-roots} set: a host volume under an approved root is
 * accepted, the reserved tag is stripped from the cluster's public tags, and the stored host
 * volume is internal metadata that never appears in a DescribeCluster or ListTagsForResource
 * response.
 */
@QuarkusTest
@TestProfile(EksHostVolumesAllowedIntegrationTest.HostVolumeRootsProfile.class)
class EksHostVolumesAllowedIntegrationTest {

    private static final String JSON = "application/json";
    private static final String ROOT = "/srv/floci-host-volume-it";

    public static class HostVolumeRootsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.eks.host-volume-roots", ROOT);
        }
    }

    @Test
    void hostVolumeUnderApprovedRootIsAcceptedAndKeptOffTheWire() {
        String cluster = "host-volume-it-allowed";
        given().contentType(JSON)
                .body("{\"name\":\"" + cluster + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"resourcesVpcConfig\":{},"
                        + "\"tags\":{\"floci:host-volume:workspace\":\"" + ROOT + "/projects:/workspace\","
                        + "\"team\":\"data\"}}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(cluster))
                .body("cluster", not(hasKey("hostVolumes")))
                .body("cluster.tags", equalTo(Map.of("team", "data")));

        given()
                .when().get("/clusters/" + cluster)
                .then().statusCode(200)
                .body("cluster", not(hasKey("hostVolumes")))
                .body("cluster.tags", equalTo(Map.of("team", "data")));
    }
}
