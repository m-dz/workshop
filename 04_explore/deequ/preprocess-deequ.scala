import com.amazon.deequ.analyzers.runners.{AnalysisRunner, AnalyzerContext}
import com.amazon.deequ.analyzers.runners.AnalyzerContext.successMetricsAsDataFrame
import com.amazon.deequ.analyzers.{Compliance, Correlation, Size, Completeness, Mean, ApproxCountDistinct}
import com.amazon.deequ.{VerificationSuite, VerificationResult}
import com.amazon.deequ.VerificationResult.checkResultsAsDataFrame
import com.amazon.deequ.checks.{Check, CheckLevel}
import com.amazon.deequ.suggestions.{ConstraintSuggestionRunner, Rules}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.types.{StructType, StructField, StringType, IntegerType}


object SparkAmazonReviewsAnalyzer {
  def run(s3InputData: String, s3OutputAnalyzeData: String): Unit = {
    // TODO:  Retrieve s3_input_data and s3_output_analyze_data args from the list of args (argv-style)
//    System.out.println(args)
    System.out.println(s"s3_input_data: ${s3InputData}")
    System.out.println(s"s3_output_analyze_data: ${s3OutputAnalyzeData}")
      
    val spark = SparkSession
      .builder
      .appName("SparkAmazonReviewsAnalyzer")
      .getOrCreate()
    
    // Uncomment this to read parquet instead
    //val dataset = spark.read.parquet(s3InputData)  

    val schema = StructType(Array(
//        StructField("is_positive_sentiment", IntegerType, true),
        StructField("marketplace", StringType, true),
        StructField("customer_id", StringType, true),
        StructField("review_id", StringType, true),
        StructField("product_id", StringType, true),
        StructField("product_parent", StringType, true),
        StructField("product_title", StringType, true),
        StructField("product_category", StringType, true),
        StructField("star_rating", IntegerType, true),
        StructField("helpful_votes", IntegerType, true),
        StructField("total_votes", IntegerType, true),
        StructField("vine", StringType, true),
        StructField("verified_purchase", StringType, true),
        StructField("review_headline", StringType, true),
        StructField("review_body", StringType, true),
        StructField("review_date", StringType, true)
    ))

    val dataset = spark.read.option("sep", "\t")
                            .option("header", "true")
                            .option("quote", "")
                            .schema(schema)
                            .csv(s3InputData)

    val analysisResult: AnalyzerContext = { AnalysisRunner
          // data to run the analysis on
          .onData(dataset)
          // define analyzers that compute metrics
          .addAnalyzer(Size())
          .addAnalyzer(Completeness("review_id"))
          .addAnalyzer(ApproxCountDistinct("review_id"))
          .addAnalyzer(Mean("star_rating"))
          .addAnalyzer(Compliance("top star_rating", "star_rating >= 4.0"))
          .addAnalyzer(Correlation("total_votes", "star_rating"))
          .addAnalyzer(Correlation("total_votes", "helpful_votes"))
          // compute metrics
          .run()
        }

    // retrieve successfully computed metrics as a Spark data frame
    val metrics = successMetricsAsDataFrame(spark, analysisResult)
    metrics.show(truncate=false)
    metrics
      .repartition(1)
      .write
      .mode(SaveMode.Overwrite)
      .option("header", true)      
      .option("delimiter", "\t")
      .csv(s"${s3OutputAnalyzeData}/dataset-metrics")

    val verificationResult: VerificationResult = { VerificationSuite()
          // data to run the verification on
          .onData(dataset)
          // define data quality checks,
          // compute metrics 
          //verify check conditions
          .addCheck(
            Check(CheckLevel.Error, "Review Check") 
              .hasSize(_ >= 150000000) // at least 150 million rows
              .hasMin("star_rating", _ == 1.0) // min is 1.0
              .hasMax("star_rating", _ == 5.0) // max is 5.0
              .isComplete("review_id") // should never be NULL
              .isUnique("review_id") // should not contain duplicates
              .isComplete("marketplace") // should never be NULL
              // contains only the listed values
              .isContainedIn("marketplace", Array("US", "UK", "DE", "JP", "FR"))
//  TODO:  This is not working in deequ-1.0.1.jar
//              .isNonNegative("year")) // should not contain negative values  
              )
          .run()
    }

    // convert check results to a Spark data frame
    val resultsDataFrame = checkResultsAsDataFrame(spark, verificationResult)
    resultsDataFrame.show(truncate=false)
    resultsDataFrame
      .repartition(1)
      .write
      .mode(SaveMode.Overwrite)
      .option("header", true)
      .option("delimiter", "\t")
      .csv(s"${s3OutputAnalyzeData}/constraint-checks")
    
    val verificationSuccessMetricsDataFrame = VerificationResult
      .successMetricsAsDataFrame(spark, verificationResult)

    verificationSuccessMetricsDataFrame.show(truncate=false)
    verificationSuccessMetricsDataFrame
      .repartition(1)
      .write
      .mode(SaveMode.Overwrite)
      .option("header", true)
      .option("delimiter", "\t")
      .csv(s"${s3OutputAnalyzeData}/success-metrics")      

    // We ask deequ to compute constraint suggestions for us on the data
    val suggestionsResult = { ConstraintSuggestionRunner()
          // data to suggest constraints for
          .onData(dataset)
          // default set of rules for constraint suggestion
          .addConstraintRules(Rules.DEFAULT)
          // run data profiling and constraint suggestion
          .run()
    }

    import spark.implicits._ // for toDS method below

    // We can now investigate the constraints that Deequ suggested. 
    val suggestionsDataFrame = suggestionsResult.constraintSuggestions.flatMap { 
          case (column, suggestions) => 
            suggestions.map { constraint =>
              (column, constraint.description, constraint.codeForConstraint)
            } 
    }.toSeq.toDS()
      
    suggestionsDataFrame.show(truncate=false)
    suggestionsDataFrame
      .repartition(1)      
      .write      
      .mode(SaveMode.Overwrite)
      .option("header", true)  
      .option("delimiter", "\t")
      .csv(s"${s3OutputAnalyzeData}/constraint-suggestions")      
  }
}
