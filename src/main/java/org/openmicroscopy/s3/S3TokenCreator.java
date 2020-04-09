package org.openmicroscopy.s3;
import com.amazonaws.auth.policy.*;
import com.amazonaws.auth.policy.actions.S3Actions;
import com.amazonaws.auth.policy.conditions.StringCondition;
import com.amazonaws.auth.policy.resources.S3BucketResource;
import com.amazonaws.auth.policy.resources.S3ObjectResource;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import org.apache.commons.cli.*;

import static com.amazonaws.auth.policy.conditions.StringCondition.StringComparisonType.StringLike;
import static com.google.common.base.Strings.nullToEmpty;


/**
 * A simple connection to an OMERO server using the Java gateway
 *
 * @author The OME Team
 */
public class S3TokenCreator {

    /**
     * Create a JSON policy string
     * @param bucket
     * @param prefix
     * @return Policy as a JSON string
     */
    private String getPolicy(String bucket, String prefix) {
        // https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies.html#policies_session
        // https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements.html
        // https://aws.amazon.com/premiumsupport/knowledge-center/s3-folder-user-access/

        Statement bucketAccess = new Statement(Statement.Effect.Allow)
                .withId("ListObjectsInBucket")
                .withActions(S3Actions.ListObjects)
                .withResources(new S3BucketResource(bucket))
                .withConditions(
                        new StringCondition(StringLike, "s3:prefix", prefix)
                );
        Statement objectAccess = new Statement(Statement.Effect.Allow)
                .withId("GetObjectsInBucket")
                .withActions(S3Actions.GetObject)
                .withResources(new S3ObjectResource(bucket, prefix));
        Policy policy = new Policy()
                .withStatements(bucketAccess, objectAccess);
        return policy.toJson();
    }

    public AssumeRoleResult createToken(String endpoint, String region, String bucket, String prefix) {
        AWSSecurityTokenServiceClientBuilder builder = AWSSecurityTokenServiceClientBuilder.standard();
        if (!endpoint.isEmpty()) {
            builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, region));
        } else if (!region.isEmpty()) {
            builder.setRegion(region);
        }

        AWSSecurityTokenService client = builder.build();

        AssumeRoleRequest request = new AssumeRoleRequest()
                .withDurationSeconds(900)
                .withPolicy(getPolicy(bucket, prefix))
                .withRoleArn("arn:x:ignored:by:minio:")
                .withRoleSessionName("ignored-by-minio");
        return client.assumeRole(request);
    }

    private static void addOption(Options options, String opt, boolean required, String help) {
        Option option = new Option(opt, true, help);
        option.setRequired(required);
        options.addOption(option);
    }

    private static String dquote(String s) {
        return '"' + s.replace("\"", "\\\"") + '"';
    }

    /**
     * Create a token
     */
    public static void main(String[] args) throws Exception {
        Options options = new Options();
        addOption(options, "endpoint", false, "S3 server endpoint (optional)");
        addOption(options, "region", false, "S3 region (optional)");
        addOption(options, "bucket", true, "S3 bucket (required)");
        addOption(options, "prefix", true, "Prefix inside bucket, for example *, prefix/*, prefix/file.name (required)");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter help = new HelpFormatter();
        CommandLine cmd = null;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            String header = "Create a temporary S3 access token using the Security Token Service. " +
                    "Credentials are read using the AWS Default Credential Provider Chain.";
            help.printHelp("S3TokenCreator", options);
            System.exit(1);
        }

        String endpoint = nullToEmpty(cmd.getOptionValue("endpoint"));
        String region = nullToEmpty(cmd.getOptionValue("region"));
        S3TokenCreator client = new S3TokenCreator();
        AssumeRoleResult result = client.createToken(
                endpoint,
                region,
                nullToEmpty(cmd.getOptionValue("bucket")),
                nullToEmpty(cmd.getOptionValue("prefix")));

        String jsonOutput =
                "{" + dquote("endpoint_url") + ":" + dquote(endpoint) +
                "," + dquote("region_name") + ":" + dquote(region) +
                "," + dquote("aws_access_key_id") + ":" + dquote(result.getCredentials().getAccessKeyId()) +
                "," + dquote("aws_secret_access_key") + ":" + dquote(result.getCredentials().getSecretAccessKey()) +
                "," + dquote("aws_session_token") + ":" + dquote(result.getCredentials().getSessionToken()) +
                "}";
        System.out.println(jsonOutput);
    }
}
