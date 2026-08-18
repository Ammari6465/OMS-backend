package com.sunrich.oms.workplace

import com.sunrich.oms.exception.BadRequestException
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files

class FloorPlanStorageTest{
 private fun storage(max:Long=1024)=FloorPlanStorage(Files.createTempDirectory("oms-plans").toString(),max)
 @Test fun `accepts safe svg using a generated storage name`(){val file=MockMultipartFile("file","../../../plan.svg","image/svg+xml","<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"80\"><rect width=\"10\" height=\"10\"/></svg>".toByteArray());val saved=storage().store(file);assertThat(saved.reference).matches("[a-f0-9-]{36}\\.svg");assertThat(saved.originalName).isEqualTo("plan.svg");assertThat(saved.width).isEqualTo(100)}
 @Test fun `rejects scripts external references and malformed formats`(){val unsafe=MockMultipartFile("file","x.svg","image/svg+xml","<svg><script>alert(1)</script></svg>".toByteArray());assertThatThrownBy{storage().store(unsafe)}.isInstanceOf(BadRequestException::class.java);val fake=MockMultipartFile("file","x.png","image/png","not a png".toByteArray());assertThatThrownBy{storage().store(fake)}.isInstanceOf(BadRequestException::class.java)}
 @Test fun `enforces size and prevents path traversal`(){val s=storage(4);assertThatThrownBy{s.store(MockMultipartFile("file","x.png","image/png",ByteArray(5)))}.isInstanceOf(BadRequestException::class.java);assertThatThrownBy{s.read("../secret.svg")}.isInstanceOf(BadRequestException::class.java)}
}
