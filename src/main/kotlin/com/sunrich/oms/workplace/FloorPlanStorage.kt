package com.sunrich.oms.workplace

import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ResourceNotFoundException
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

 init{
  // Uploaded plans live on disk while their metadata lives in the database. On
  // a container without a persistent mount here, every restart silently drops
  // the images and leaves floors pointing at files that no longer exist, so the
  // resolved path is logged where it can be checked against the mount.
  val log=org.slf4j.LoggerFactory.getLogger(javaClass)
  val mount=System.getenv("RAILWAY_VOLUME_MOUNT_PATH")
  val existing=runCatching{Files.list(root).use{it.count()}}.getOrDefault(0L)
  if(mount!=null&&Paths.get(mount).toAbsolutePath().normalize()!=root){
   log.warn("Floor plans are stored at {} but the persistent volume is mounted at {}. Uploads will be lost on restart.",root,mount)
  }else{
   log.info("Floor plan storage at {} ({} files, persistent volume: {})",root,existing,mount!=null)
  }
 }

 fun store(file:MultipartFile):StoredPlan{
  if(file.isEmpty)throw BadRequestException("Floor plan file is empty");if(file.size>maxBytes)throw BadRequestException("Floor plan exceeds the configured size limit")
  val bytes=file.bytes;val detected=detect(bytes,file.contentType);val ext=when(detected){"image/png"->"png";"image/jpeg"->"jpg";else->"svg"}
  val dimensions=if(detected=="image/svg+xml")svgDimensions(bytes)else rasterDimensions(bytes)
  val name="${UUID.randomUUID()}.$ext";val target=resolve(name);Files.write(target,bytes,StandardOpenOption.CREATE_NEW)
  return StoredPlan(name,(file.originalFilename?:"floor-plan.$ext").substringAfterLast('/').substringAfterLast('\\').take(255),detected,dimensions?.first,dimensions?.second)
 }

 private fun rasterDimensions(bytes:ByteArray):Pair<Int,Int> = runCatching{ImageIO.read(bytes.inputStream())}.getOrNull()?.let{it.width to it.height} ?: throw BadRequestException("The floor plan image could not be read. Upload a valid PNG, JPEG, or SVG file")

 /**
  * Whether the stored file backing [reference] is actually present. Floor rows
  * keep their plan metadata in the database while the image lives on disk, so
  * the two can drift apart — a restore from a database-only backup, or a
  * container rebuilt without its plan volume. Callers use this to report
  * `hasPlan` honestly instead of handing out a link that 404s.
  */
 fun exists(reference:String?):Boolean =
  reference!=null && runCatching{Files.exists(resolve(reference))}.getOrDefault(false)

 fun read(reference:String):ByteArray{
  val target=resolve(reference)
  if(!Files.exists(target))throw ResourceNotFoundException("Floor plan")
  return try{Files.readAllBytes(target)}catch(e:NoSuchFileException){throw ResourceNotFoundException("Floor plan")}
 }

 fun delete(reference:String?){reference?.let{runCatching{Files.deleteIfExists(resolve(it))}}}

 private fun resolve(reference:String):Path{
  if(!reference.matches(Regex("^[a-f0-9-]{36}\\.(png|jpg|svg)$")))throw BadRequestException("Invalid floor plan reference")
  return root.resolve(reference).normalize().also{if(!it.startsWith(root))throw BadRequestException("Invalid floor plan reference")}
 }

 private fun detect(b:ByteArray,declared:String?):String{
  if(b.size>=8&&b.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)))return "image/png"
  if(b.size>=3&&b[0]==0xff.toByte()&&b[1]==0xd8.toByte()&&b[2]==0xff.toByte())return "image/jpeg"
  val text=b.toString(Charsets.UTF_8).replace("\uFEFF","").trimStart()
  val isSvgDeclared=declared?.lowercase()?.let{it.contains("svg")||it=="image/svg+xml"}==true
  if((isSvgDeclared||text.startsWith("<"))&&text.contains(Regex("<svg[\\s>]",RegexOption.IGNORE_CASE))){
   validateSvg(b)
   return "image/svg+xml"
  }
  throw BadRequestException("Only valid PNG, JPEG, and SVG floor plans are accepted")
 }

 /**
  * Two-layer SVG safety. A cheap regex pre-pass rejects the obvious, and no
  * DOCTYPE/entity declaration is accepted at all (defence-in-depth against XXE
  * and entity-expansion, on top of the external-entity-disabled parser). Then
  * the parsed document is walked with an **allow-list**: any element not on the
  * safe drawing list, any event-handler attribute, any external/js/non-image
  * href, and any @import / url(http) / @font-face / expression() CSS is rejected.
  * An allow-list blocks unknown and future vectors that a deny-list would miss.
  */
 private fun validateSvg(bytes:ByteArray){
  val text=bytes.toString(Charsets.UTF_8).replace("\uFEFF","")
  // Reject entity declarations (the XXE / entity-expansion vector). A plain
  // SVG 1.1 DOCTYPE with no <!ENTITY> is left to the parser, which already has
  // external DTD/entity resolution disabled, so benign real-world exports load.
  if(Regex("<!ENTITY",RegexOption.IGNORE_CASE).containsMatchIn(text))
   throw BadRequestException("SVG must not declare XML entities")
  if(Regex("<(script|foreignObject|iframe|object|embed)[\\s>]",RegexOption.IGNORE_CASE).containsMatchIn(text)||
     Regex("\\son[a-z]+\\s*=",RegexOption.IGNORE_CASE).containsMatchIn(text)||
     Regex("(?:href|src)\\s*=\\s*['\"]\\s*javascript:",RegexOption.IGNORE_CASE).containsMatchIn(text)||
     Regex("(?:href|src)\\s*=\\s*['\"]\\s*data:(?!image/(?:png|jpeg|jpg|webp|gif|svg\\+xml);base64,)",RegexOption.IGNORE_CASE).containsMatchIn(text)||
     Regex("(?:href|src)\\s*=\\s*['\"]\\s*https?:",RegexOption.IGNORE_CASE).containsMatchIn(text)){
   throw BadRequestException("SVG contains unsafe content")
  }
  val doc=try{parseSvg(bytes)}catch(e:BadRequestException){throw e}catch(e:Exception){throw BadRequestException("The SVG file is invalid or malformed")}
  validateSvgDom(doc)
 }

 /** Elements a floor-plan SVG is allowed to contain — drawing, text and structure only; no active or embedding elements. */
 private val allowedSvgElements=setOf("svg","g","defs","symbol","use","title","desc","metadata","style","rect","circle","ellipse","line","polyline","polygon","path","text","tspan","textpath","tref","image","clippath","mask","pattern","marker","lineargradient","radialgradient","stop","view","switch","a")

 private fun validateSvgDom(doc:org.w3c.dom.Document){
  val stack=ArrayDeque<org.w3c.dom.Node>();stack.addLast(doc.documentElement)
  while(stack.isNotEmpty()){
   val node=stack.removeLast()
   if(node is org.w3c.dom.Element){
    val name=(node.localName?:node.tagName.substringAfterLast(':')).lowercase()
    if(name !in allowedSvgElements)throw BadRequestException("SVG contains a disallowed element <$name>")
    val attrs=node.attributes
    for(i in 0 until attrs.length){
     val a=attrs.item(i);val an=(a.localName?:a.nodeName).lowercase();val av=(a.nodeValue?:"")
     if(an.startsWith("on"))throw BadRequestException("SVG contains an event-handler attribute ${a.nodeName}")
     if(an=="href"||a.nodeName.lowercase().endsWith("href")){
      val v=av.trim().lowercase()
      if(v.startsWith("http:")||v.startsWith("https:")||v.startsWith("javascript:")||(v.startsWith("data:")&&!v.startsWith("data:image/")))
       throw BadRequestException("SVG references an external or unsafe URL")
     }
     if(an=="style"&&unsafeCss(av))throw BadRequestException("SVG style attribute contains unsafe CSS")
    }
    if(name=="style"&&unsafeCss(node.textContent?:""))throw BadRequestException("SVG <style> contains unsafe CSS")
   }
   val kids=node.childNodes;for(i in 0 until kids.length)stack.addLast(kids.item(i))
  }
 }
 private fun unsafeCss(css:String):Boolean{val c=css.lowercase();return c.contains("@import")||c.contains("@font-face")||c.contains("expression(")||Regex("url\\(\\s*['\"]?\\s*(?:https?:|//)").containsMatchIn(c)}

 private fun parseSvg(bytes:ByteArray)=DocumentBuilderFactory.newInstance().apply{
  isNamespaceAware=true
  setFeature("http://xml.org/sax/features/external-general-entities",false)
  setFeature("http://xml.org/sax/features/external-parameter-entities",false)
  setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false)
  runCatching{setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"")}
  runCatching{setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")}
  isExpandEntityReferences=false
 }.newDocumentBuilder().parse(bytes.inputStream())

 private fun svgDimensions(bytes:ByteArray):Pair<Int,Int>?=runCatching{
  val e=parseSvg(bytes).documentElement
  val wAttr=e.getAttribute("width").filter{it.isDigit()||it=='.'}.toDoubleOrNull()?.toInt()
  val hAttr=e.getAttribute("height").filter{it.isDigit()||it=='.'}.toDoubleOrNull()?.toInt()
  if(wAttr!=null&&hAttr!=null&&wAttr>0&&hAttr>0)return@runCatching wAttr to hAttr
  val viewBox=e.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).mapNotNull{it.toDoubleOrNull()?.toInt()}
  if(viewBox.size==4&&viewBox[2]>0&&viewBox[3]>0)return@runCatching viewBox[2] to viewBox[3]
  null
 }.getOrNull()
}

