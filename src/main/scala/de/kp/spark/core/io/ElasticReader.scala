package de.kp.spark.core.io
/* Copyright (c) 2014 Dr. Krusche & Partner PartG
* 
* This file is part of the Spark-Core project
* (https://github.com/skrusche63/spark-core).
* 
* Spark-Core is free software: you can redistribute it and/or modify it under the
* terms of the GNU General Public License as published by the Free Software
* Foundation, either version 3 of the License, or (at your option) any later
* version.
* 
* Spark-Core is distributed in the hope that it will be useful, but WITHOUT ANY
* WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
* A PARTICULAR PURPOSE. See the GNU General Public License for more details.
* You should have received a copy of the GNU General Public License along with
* Spark-Core. 
* 
* If not, see <http://www.gnu.org/licenses/>.
*/

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import org.apache.hadoop.io.{ArrayWritable,MapWritable,NullWritable,Text}

import org.elasticsearch.node.NodeBuilder._
import org.elasticsearch.common.logging.Loggers

import de.kp.spark.core.{Configuration => Config,Names}

import org.apache.hadoop.conf.{Configuration => HadoopConfig}
import org.elasticsearch.hadoop.mr.EsInputFormat

import scala.collection.JavaConversions._

class ElasticReader extends Serializable {
  /*
   * Create an Elasticsearch node by interacting with
   * the Elasticsearch server on the local machine
   */
  private val node = nodeBuilder().node()
  private val client = node.client()
  
  private val logger = Loggers.getLogger(getClass())
  
  def read(@transient sc:SparkContext,config:HadoopConfig):RDD[Map[String,String]] = {

    val source = sc.newAPIHadoopRDD(config, classOf[EsInputFormat[Text, MapWritable]], classOf[Text], classOf[MapWritable])
    source.map(hit => toMap(hit._2))
    
  }
  
  def read(@transient sc:SparkContext,config:Config,index:String,mapping:String,query:String):RDD[Map[String,String]] = {
          
    val conf = config.elastic
    /*
     * Append dynamic request specific data to Elasticsearch configuration;
     * this comprises the search query to be used and the index (and mapping)
     * to be accessed
     */
    conf.set(Names.ES_QUERY,query)
    conf.set(Names.ES_RESOURCE,(index + "/" + mapping))
 
    read(sc,conf)
    
  }

  def open(index:String,mapping:String):Boolean = {
        
    val readyToRead = try {
      
      val indices = client.admin().indices
      /*
       * Check whether referenced index exists; if index does not
       * exist, through exception
       */
      val existsRes = indices.prepareExists(index).execute().actionGet()            
      if (existsRes.isExists() == false) {
        new Exception("Index '" + index + "' does not exist.")            
      }

      /*
       * Check whether the referenced mapping exists; if mapping
       * does not exist, through exception
       */
      val prepareRes = indices.prepareGetMappings(index).setTypes(mapping).execute().actionGet()
      if (prepareRes.mappings().isEmpty) {
        new Exception("Mapping '" + index + "/" + mapping + "' does not exist.")
      }
      
      true

    } catch {
      case e:Exception => {
        
        logger.error(e.getMessage())
        false
        
      }
       
    }
    
    readyToRead
    
  }

  def exists(index:String,mapping:String,id:String):Boolean = {
    
    val response = client.prepareGet(index,mapping,id).execute().actionGet()
    if (response.isExists()) true else false

  }
  
  def close() {
    if (node != null) node.close()
  }
  
  private def toMap(mw:MapWritable):Map[String,String] = {
      
    val m = mw.map(e => {
        
      val k = e._1.toString        
      val v = (if (e._2.isInstanceOf[Text]) e._2.toString()
        else if (e._2.isInstanceOf[ArrayWritable]) {
        
          val array = e._2.asInstanceOf[ArrayWritable].get()
          array.map(item => {
            
            (if (item.isInstanceOf[NullWritable]) "" else item.asInstanceOf[Text].toString)}).mkString(",")
            
        }
        else "")
        
    
      k -> v
        
    })
      
    m.toMap
    
  }

}