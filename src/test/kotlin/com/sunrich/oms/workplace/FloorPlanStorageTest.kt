package com.sunrich.oms.workplace

import com.sunrich.oms.exception.BadRequestException
import com.sunrich.oms.exception.ResourceNotFoundException
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files

class FloorPlanStorageTest{
 private fun storage(max:Long=1024)=FloorPlanStorage(Files.createTempDirectory("oms-plans").toString(),max)
 @Test fun `accepts safe svg using a generated storage name`(){val file=MockMultipartFile("file","../../../plan.svg","image/svg+xml","<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"80\"><rect width=\"10\" height=\"10\"/></svg>".toByteArray());val saved=storage().store(file);assertThat(saved.reference).matches("[a-f0-9-]{36}\\.svg");assertThat(saved.originalName).isEqualTo("plan.svg");assertThat(saved.width).isEqualTo(100)}
 @Test fun `accepts svg with DOCTYPE declaration and viewBox dimensions`(){
  val svgContent="""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 800">
  <rect width="100" height="100" fill="#eee"/>
</svg>"""
  val file=MockMultipartFile("file","floor.svg","image/svg+xml",svgContent.toByteArray())
  val saved=storage().store(file)
  assertThat(saved.width).isEqualTo(1200)
  assertThat(saved.height).isEqualTo(800)
 }
 @Test fun `accepts svg with UTF-8 BOM and safe embedded data URI image`(){
  val bom=byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())
  val svgContent="""<svg xmlns="http://www.w3.org/2000/svg" width="500" height="400">
  <image href="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==" width="500" height="400"/>
</svg>"""
  val file=MockMultipartFile("file","floor_bom.svg","image/svg+xml",bom+svgContent.toByteArray(Charsets.UTF_8))
  val saved=storage().store(file)
  assertThat(saved.width).isEqualTo(500)
 }
 @Test fun `rejects scripts external references and malformed formats`(){val unsafe=MockMultipartFile("file","x.svg","image/svg+xml","<svg><script>alert(1)</script></svg>".toByteArray());assertThatThrownBy{storage().store(unsafe)}.isInstanceOf(BadRequestException::class.java);val fake=MockMultipartFile("file","x.png","image/png","not a png".toByteArray());assertThatThrownBy{storage().store(fake)}.isInstanceOf(BadRequestException::class.java)}
 @Test fun `allow-list rejects a disallowed element even without an event handler`(){
  // <foreignObject> carries no on* handler, so only an allow-list (not a deny-list) catches it.
  val svg=MockMultipartFile("file","x.svg","image/svg+xml","<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><foreignObject/></svg>".toByteArray())
  assertThatThrownBy{storage().store(svg)}.isInstanceOf(BadRequestException::class.java)
 }
 @Test fun `rejects entity declarations, external images and unsafe CSS`(){
  val entity=MockMultipartFile("file","x.svg","image/svg+xml","<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>".toByteArray())
  assertThatThrownBy{storage().store(entity)}.isInstanceOf(BadRequestException::class.java)
  val extImg=MockMultipartFile("file","y.svg","image/svg+xml","<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><image href=\"https://evil.example/x.png\"/></svg>".toByteArray())
  assertThatThrownBy{storage().store(extImg)}.isInstanceOf(BadRequestException::class.java)
  val css=MockMultipartFile("file","z.svg","image/svg+xml","<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><style>@import url(https://evil.example/x.css);</style></svg>".toByteArray())
  assertThatThrownBy{storage().store(css)}.isInstanceOf(BadRequestException::class.java)
 }
 @Test fun `rejects a raster that cannot be decoded instead of failing at write time`(){val truncated=MockMultipartFile("file","broken.png","image/png",byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)+ByteArray(16));val dir=Files.createTempDirectory("oms-plans-reject");val s=FloorPlanStorage(dir.toString(),1024);assertThatThrownBy{s.store(truncated)}.isInstanceOf(BadRequestException::class.java);assertThat(Files.list(dir).use{it.count()}).isZero()}
 @Test fun `enforces size and prevents path traversal`(){val s=storage(4);assertThatThrownBy{s.store(MockMultipartFile("file","x.png","image/png",ByteArray(5)))}.isInstanceOf(BadRequestException::class.java);assertThatThrownBy{s.read("../secret.svg")}.isInstanceOf(BadRequestException::class.java)}
 @Test fun `read throws ResourceNotFoundException when file is missing from disk`(){
  val s=storage()
  val uuid="00000000-0000-0000-0000-000000000001.png"
  assertThatThrownBy{s.read(uuid)}.isInstanceOf(ResourceNotFoundException::class.java)
 }
 @Test fun `exists reports stored files and never throws on missing or invalid references`(){
  val s=storage()
  val saved=s.store(MockMultipartFile("file","plan.svg","image/svg+xml","<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>".toByteArray()))
  assertThat(s.exists(saved.reference)).isTrue()
  assertThat(s.exists("00000000-0000-0000-0000-000000000001.png")).isFalse()
  // A dangling or malformed reference must degrade to "no plan", not blow up
  // the floor listing that asks about it.
  assertThat(s.exists("../secret.svg")).isFalse()
  assertThat(s.exists(null)).isFalse()
 }
}

