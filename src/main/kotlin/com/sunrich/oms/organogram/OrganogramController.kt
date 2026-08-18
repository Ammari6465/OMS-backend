package com.sunrich.oms.organogram

import com.sunrich.oms.common.dto.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/organogram")
class OrganogramController(private val service: OrganogramService) {
    @GetMapping
    fun get(@RequestParam companyId: Long, @RequestParam(defaultValue = "EMPLOYEE") view: OrganogramView,
        @RequestParam(defaultValue = "true") includeVacancies: Boolean) =
        ApiResponse.ok(service.get(companyId, view, includeVacancies))

    @GetMapping("/staff-details")
    fun staffDetails(@RequestParam staffId: Long) = ApiResponse.ok(service.staffDetails(staffId))
}
