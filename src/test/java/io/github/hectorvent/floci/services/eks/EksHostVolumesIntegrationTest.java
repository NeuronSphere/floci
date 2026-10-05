package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Host volumes requested through the reserved {@code floci:host-volume:<label>} creation tag are
 * rejected unless an operator opts in with {@code floci.services.eks.host-volume-roots} or
 * {@code floci.services.eks.allow-unsafe-host-volumes}. The test profile configures neither, so
 * CreateCluster must answer with the model-declared InvalidParameterException and create nothing.
 */
@QuarkusTest
class EksHostVolumesIntegrationTest {

    private static final String JSON = "application/json";

    @Test
    void createClusterWithHostVolumeIsRejectedWithoutOptIn() {
        String cluster = "host-volume-it-rejected";
        given().contentType(JSON)
                .body("{\"name\":\"" + cluster + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"resourcesVpcConfig\":{},"
                        + "\"tags\":{\"floci:host-volume:workspace\":\"/srv/projects:/workspace\"}}")
                .when().post("/clusters")
                .then().statusCode(400)
                .contentType(containsString("application/json"))
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", containsString("floci.services.eks.host-volume-roots"));

        given()
                .when().get("/clusters/" + cluster)
                .then().statusCode(404);
    }

    @Test
    void createClusterWithMalformedHostVolumeIsRejected() {
        given().contentType(JSON)
                .body("{\"name\":\"host-volume-it-malformed\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"resourcesVpcConfig\":{},"
                        + "\"tags\":{\"floci:host-volume:bad\":\"/srv/projects\"}}")
                .when().post("/clusters")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", containsString("floci:host-volume:bad"));
    }
}
