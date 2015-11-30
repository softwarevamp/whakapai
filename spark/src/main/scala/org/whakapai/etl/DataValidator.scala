/*
 * whakapai: etl on spark
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */


package org.whakapai.etl

import org.whakapai.common.JobConfiguration
import org.apache.spark.SparkContext
import org.chombo.util.Utility
import org.chombo.validator.ValidatorFactory
import com.typesafe.config.Config
import org.chombo.validator.Validator
import org.chombo.util.ProcessorAttributeSchema
import org.chombo.util.NumericalAttrStatsManager
import org.chombo.util.MedianStatsManager
import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap

/**
@author pranab
 *
* */
object DataValidator extends JobConfiguration  {
  var statsManager: Option[NumericalAttrStatsManager] = None
  var medStatManager : Option[MedianStatsManager] = None
  val validationContext = new java.util.HashMap[String, Object]()
  val mutValidators : scala.collection.mutable.HashMap[Int, Array[Validator]]   = scala.collection.mutable.HashMap()
  lazy val validators :  Map[Int, Array[Validator]]   = mutValidators.toMap
    
    
 /**
 * @param args
 * @return
 */
   def main(args: Array[String]) {
	   val Array(master: String, inputPath: String, outputPath: String, configFile: String) = getCommandLineArgs(args, 3)
	   val config = createConfig(configFile)
	   val sparkConf = createSparkConf("app.data validation", config)
	   val sparkCntxt = new SparkContext(sparkConf)
	   
	   val filterInvalidRecords = config.getBoolean("app.filter.invalid.records")
	   val validationSchema = Utility.getProcessingSchema( config.getString("app.schema.file.path")) 
	   val validatorConfig = Utility.getHoconConfig(config.getString("app.hconf.file.path"))
	   ValidatorFactory.initialize( config.getString( "app,custom.valid.factory.class"), validatorConfig )
	   val ordinals =  validationSchema.getAttributeOrdinals()
	   val tagSep = config.getString( "app,vaidator.tag.separator")
	   
	   //initialize stats manager
	   getAttributeStats(config.getString("app.stats.file.path"))
	   getAttributeMeds(config.getString("app.med.stats.file.path"), config.getString("app.mad.stats.file.path"), 
	       Utility.intArrayFromString(config.getString("app.id.ordinals"), ",") )
	  

	   //simple validators  
	   var foundSimpleValidators = false
	   ordinals.foreach(ord => {
		   val  key = "app.validator." + ord
		   if (config.hasPath(key)) {
			   val validatorTag = config.getString(key)
			   val valTags = validatorTag.split(tagSep);
			   createValidators(config, valTags, ord, validationSchema, mutValidators)
			   foundSimpleValidators = true
		   }
	   })
	   
	   //complex validators
	   if (!foundSimpleValidators) {
	      validationSchema.getAttributes().asScala.foreach( attr  => {
	    	  	if (null != attr.getValidators()) {
	    	  		val validatorTags =  attr.getValidators().asScala.toArray
	    	  		createValidators(config, validatorTags, attr.getOrdinal(), validationSchema, mutValidators)
	    	  	}
	      })
	   }
	   
	   
   }
   
   /**
 * @param config
 * @param valTags
 * @param ord
 * @param validatorConfig
 * @param validationSchema
 */
private  def createValidators( config : Config , valTags : Array[String],   ord : Int,
       validationSchema :  ProcessorAttributeSchema, mutValidators : scala.collection.mutable.HashMap[Int, Array[Validator]])  {
	   val validatorList =  List[Validator]()
	   val  prAttr = validationSchema.findAttributeByOrdinal(ord)
	   val validatorConfig = config.atPath("app")
	   val validators = valTags.map(tag => {
		    val validator = tag match {
		     case "zscoreBasedRange" => {
		    	 getAttributeStats(config.getString("app.stats.file.path"))
		    	 ValidatorFactory.create(tag, prAttr, validationContext)
		     }
		     
		     case "robustZscoreBasedRange" => {
		    	 getAttributeMeds(config.getString("app.med.stats.file.path"), config.getString("app.mad.stats.file.path"), 
		    			 Utility.intArrayFromString(config.getString("app.id.ordinals"), ",") )		       
		    	 ValidatorFactory.create(tag, prAttr,validationContext)
		     }
		    
		     case tag:String => {
		       ValidatorFactory.create(tag, prAttr,  validatorConfig)
		     }
		   }
		   validator 
	   })
	   
	   //add validators to map
	   mutValidators += ord -> validators
   }

  /**
 * @param statsFilePath
 * @return
 */
  private def getAttributeStats(statsFilePath : String) {
    statsManager = statsManager match{
     	case None => Some( new NumericalAttrStatsManager(statsFilePath, ",", true))
     	case Some(s) => statsManager
    }
    
    //validationContext.
     validationContext.clear()
    validationContext.put("stats",  statsManager.get)
  }
   
  private def getAttributeMeds(medFilePath : String, madFilePath:String, idOrdinals : Array[Int] ) {
    medStatManager = medStatManager match{
     	case None => Some(  new MedianStatsManager(medFilePath, madFilePath,  
        			",",  idOrdinals))
     	case Some(s) => medStatManager
    }
    
    //validationContext.
    validationContext.clear()
    validationContext.put("stats",  medStatManager.get)
  }
}