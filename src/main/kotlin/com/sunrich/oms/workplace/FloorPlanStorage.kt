package com.sunrich.oms.workplace

import com.sunrich.oms.exception.BadRequestException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.*
import java.util.UUID
import javax.imageio.ImageIO
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class StoredPlan(val reference:String,val originalName:String,val mediaType:String,val width:Int?,val height:Int?)

@Service
class FloorPlanStorage(
 @Value("\${oms.workplace.storage-directory:./data/workplace-plans}") directory:String,
 @Value("\${oms.workplace.max-plan-bytes:10485760}") private val maxBytes:Long
){
 private val root=Paths.get(directory).toAbsolutePath().normalize().also{Files.createDirectories(it)}
 fun store(file:MultipartFile):StoredPlan{
  if(file.isEmpty)throw BadRequestException("Floor plan file is empty");if(file.size>maxBytes)throw BadRequestException("Floor plan exceeds the configured size limit")
  val bytes=file.bytes;val detected=detect(bytes,file.contentType);val ext=when(detected){"image/png"->"png";"image/jpeg"->"jpg";else->"svg"}
  val dimensions=if(detected=="image/svg+xml")svgDimensions(bytes)else rasterDimensions(bytes)
  val name="${UUID.randomUUID()}.$ext";val target=resolve(name);Files.write(target,bytes,StandardOpenOption.CREATE_NEW)
  return StoredPlan(name,(file.originalFilename?:"floor-plan.$ext").substringAfterLast('/').substringAfterLast('\\').take(255),detected,dimensions?.first,dimensions?.second)
 }
 private fun rasterDimensions(bytes:ByteArray):Pair<Int,Int> = runCatching{ImageIO.read(bytes.inputStream())}.getOrNull()?.let{it.width to it.height} ?: throw BadRequestException("The floor plan image could not be read. Upload a valid PNG, JPEG, or SVG file")
 fun read(reference:String)=Files.readAllBytes(resolve(reference))
 fun delete(reference:String?){reference?.let{runCatching{Files.deleteIfExists(resolve(it))}}}
 private fun resolve(reference:String):Path{if(!reference.matches(Regex("^[a-f0-9-]{36}\\.(png|jpg|svg)$")))throw BadRequestException("Invalid floor plan reference");return root.resolve(reference).normalize().also{if(!it.startsWith(root))throw BadRequestException("Invalid floor plan reference")}}
 private fun detect(b:ByteArray,declared:String?):String{
  if(b.size>=8&&b.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)))return "image/png"
  if(b.size>=3&&b[0]==0xff.toByte()&&b[1]==0xd8.toByte()&&b[2]==0xff.toByte())return "image/jpeg"
  val text=b.toString(Charsets.UTF_8).trimStart();if((declared=="image/svg+xml"||text.startsWith("<"))&&text.contains(Regex("<svg[\\s>]",RegexOption.IGNORE_CASE))){validateSvg(b);return "image/svg+xml"}
  throw BadRequestException("Only valid PNG, JPEG, and SVG floor plans are accepted")
 }
 private fun validateSvg(bytes:ByteArray){val text=bytes.toString(Charsets.UTF_8);if(Regex("<(script|foreignObject|iframe|object|embed)[\\s>]",RegexOption.IGNORE_CASE).containsMatchIn(text)||Regex("\\son[a-z]+\\s*=",RegexOption.IGNORE_CASE).containsMatchIn(text)||Regex("(?:href|src)\\s*=\\s*['\"]\\s*(?:https?:|data:|javascript:)",RegexOption.IGNORE_CASE).containsMatchIn(text))throw BadRequestException("SVG contains unsafe content");parseSvg(bytes)}
 private fun parseSvg(bytes:ByteArray)=DocumentBuilderFactory.newInstance().apply{setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);setFeature("http://xml.org/sax/features/external-general-entities",false);setFeature("http://xml.org/sax/features/external-parameter-entities",false);setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")}.newDocumentBuilder().parse(bytes.inputStream())
 private fun svgDimensions(bytes:ByteArray):Pair<Int,Int>?=runCatching{val e=parseSvg(bytes).documentElement;val w=e.getAttribute("width").filter{it.isDigit()||it=='.'}.toDoubleOrNull()?.toInt();val h=e.getAttribute("height").filter{it.isDigit()||it=='.'}.toDoubleOrNull()?.toInt();if(w!=null&&h!=null)w to h else null}.getOrNull()
}
