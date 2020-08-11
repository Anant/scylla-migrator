package com.scylladb.migrator

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.dynamodbv2.{
  AmazonDynamoDBClientBuilder,
  AmazonDynamoDBStreamsClient,
  AmazonDynamoDBStreamsClientBuilder
}
import com.amazonaws.services.dynamodbv2.model.{
  CreateTableRequest,
  DescribeStreamRequest,
  ProvisionedThroughput,
  ResourceNotFoundException,
  StreamSpecification,
  StreamViewType,
  UpdateTableRequest
}
import com.scylladb.migrator.config.{ DynamoDBEndpoint, SourceSettings, TargetSettings }
import org.apache.log4j.LogManager

import scala.util.{ Failure, Success, Try }

object DynamoUtils {
  val log = LogManager.getLogger("com.scylladb.migrator.DynamoUtils")

  def replicateTableDefinition(sourceSettings: SourceSettings,
                               targetSettings: TargetSettings): Unit =
    (sourceSettings, targetSettings) match {
      case (source: SourceSettings.DynamoDB, target: TargetSettings.DynamoDB) =>
        // If non-existent, replicate
        val sourceClient = buildDynamoClient(source.endpoint, source.credentials, source.region)
        val targetClient = buildDynamoClient(target.endpoint, target.credentials, target.region)
        val sourceDescription = sourceClient.describeTable(source.table).getTable

        log.info("Checking for table existence at destination")
        val targetDescription = Try(targetClient.describeTable(target.table))
        targetDescription match {
          case Success(desc) =>
            log.info(s"Table ${source.table} exists at destination")

          case Failure(e: ResourceNotFoundException) =>
            val request = new CreateTableRequest()
              .withTableName(target.table)
              .withKeySchema(sourceDescription.getKeySchema)
              .withAttributeDefinitions(sourceDescription.getAttributeDefinitions)
              .withProvisionedThroughput(
                new ProvisionedThroughput(
                  sourceDescription.getProvisionedThroughput.getReadCapacityUnits,
                  sourceDescription.getProvisionedThroughput.getWriteCapacityUnits
                )
              )

            log.info(
              s"Table ${source.table} does not exist at destination - creating it according to definition:")
            log.info(sourceDescription.toString)
            targetClient.createTable(request)
            log.info(s"Table ${source.table} created.")

          case Failure(otherwise) =>
            throw new RuntimeException("Failed to check for table existence", otherwise)
        }
      case _ =>
        log.info("Skipping table schema replication because source/target are not both DynamoDB")
    }

  def enableDynamoStream(source: SourceSettings.DynamoDB): Unit = {
    val sourceClient = buildDynamoClient(source.endpoint, source.credentials, source.region)
    val sourceStreamsClient = buildDynamoStreamsClient(source.credentials, source.region)

    sourceClient
      .updateTable(
        new UpdateTableRequest()
          .withTableName(source.table)
          .withStreamSpecification(
            new StreamSpecification()
              .withStreamEnabled(true)
              .withStreamViewType(StreamViewType.NEW_IMAGE)
          )
      )

    var done = false
    while (!done) {
      val tableDesc = sourceClient.describeTable(source.table)
      val latestStreamArn = tableDesc.getTable.getLatestStreamArn
      val describeStream = sourceStreamsClient.describeStream(
        new DescribeStreamRequest().withStreamArn(latestStreamArn))

      val streamStatus = describeStream.getStreamDescription.getStreamStatus
      if (streamStatus == "ENABLED") {
        log.info("Stream enabled successfully")
        done = true
      } else {
        log.info(
          s"Stream not yet enabled (status ${streamStatus}); waiting for 5 seconds and retrying")
        Thread.sleep(5000)
      }
    }
  }

  def buildDynamoClient(endpoint: Option[DynamoDBEndpoint],
                        creds: Option[AWSCredentialsProvider],
                        region: Option[String]) = {
    val builder = AmazonDynamoDBClientBuilder.standard()

    endpoint.foreach { endpoint =>
      builder
        .withEndpointConfiguration(
          new AwsClientBuilder.EndpointConfiguration(
            endpoint.renderEndpoint,
            region.getOrElse("empty")))
    }
    creds.foreach(builder.withCredentials)
    region.foreach(builder.withRegion)

    builder.build()
  }

  def buildDynamoStreamsClient(creds: Option[AWSCredentialsProvider], region: Option[String]) = {
    val builder = AmazonDynamoDBStreamsClientBuilder.standard()

    creds.foreach(builder.withCredentials)
    region.foreach(builder.withRegion)

    builder.build()
  }
}
