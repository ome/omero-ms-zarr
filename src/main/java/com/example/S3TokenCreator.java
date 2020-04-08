package com.example;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
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

    public AssumeRoleResult createToken(String endpoint, String region, String accessKey, String secretKey, String bucket, String prefix) {
        AWSSecurityTokenServiceClientBuilder builder = AWSSecurityTokenServiceClientBuilder.standard();
        builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, region));
        builder.setCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)));
        AWSSecurityTokenService client = builder.build();

        AssumeRoleRequest request = new AssumeRoleRequest()
                .withDurationSeconds(900)
                .withPolicy(getPolicy(bucket, prefix))
                .withRoleArn("arn:x:ignored:by:minio:")
                .withRoleSessionName("ignored-by-minio");
        return client.assumeRole(request);
    }

    private static void addRequiredOption(Options options, String opt, String help) {
        Option option = new Option(opt, true, help);
        option.setRequired(true);
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
        addRequiredOption(options, "endpoint", "S3 server endpoint");
        addRequiredOption(options, "region", "S3 region, can be empty");
        addRequiredOption(options, "accesskey", "Access key ID for the STS admin user");
        addRequiredOption(options, "secretkey", "Secret access key ID for STS admin user");
        addRequiredOption(options, "bucket", "S3 bucket");
        addRequiredOption(options, "prefix", "Prefix inside bucket, for example *, prefix/*, prefix/file.name");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter help = new HelpFormatter();
        CommandLine cmd = null;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            help.printHelp("S3TokenCreator", options);
            System.exit(1);
        }

        String endpoint = cmd.getOptionValue("endpoint");
        String region = cmd.getOptionValue("region");
        S3TokenCreator client = new S3TokenCreator();
        AssumeRoleResult result = client.createToken(
                endpoint,
                region,
                cmd.getOptionValue("accesskey"),
                cmd.getOptionValue("secretkey"),
                cmd.getOptionValue("bucket"),
                cmd.getOptionValue("prefix"));

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
