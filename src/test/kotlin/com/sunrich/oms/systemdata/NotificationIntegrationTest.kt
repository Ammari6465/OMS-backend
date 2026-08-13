package com.sunrich.oms.systemdata

import com.sunrich.oms.common.enums.EntityStatus
import com.sunrich.oms.common.enums.NotificationType
import com.sunrich.oms.common.enums.Role
import com.sunrich.oms.exception.ResourceNotFoundException
import com.sunrich.oms.security.UserPrincipal
import com.sunrich.oms.user.User
import com.sunrich.oms.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class NotificationIntegrationTest {
 @Autowired lateinit var service:SystemDataService;@Autowired lateinit var repository:NotificationRepository;@Autowired lateinit var users:UserRepository;@Autowired lateinit var mockMvc:MockMvc
 @AfterEach fun clear()=SecurityContextHolder.clearContext()
 @Test fun `recipient can page filter and change only own read state`(){val a=user("notify-a");val b=user("notify-b");val own=repository.save(Notification(a,NotificationType.VACANCY_OPENED,"A new engineer vacancy opened"));val other=repository.save(Notification(b,NotificationType.SYSTEM,"Private"));authenticate(a);val page=service.listNotifications(0,20,"engineer",null,"VACANCY","NORMAL",false,null,null);assertThat(page.content.map{it.id}).containsExactly(own.id).doesNotContain(other.id);val updated=service.updateNotification(own.id!!,NotificationRequest(true));assertThat(updated.isRead).isTrue();assertThat(updated.readAt).isNotNull();assertThatThrownBy{service.getNotification(other.id!!)}.isInstanceOf(ResourceNotFoundException::class.java)}
 @Test fun `clients cannot forge notifications through POST`(){val a=user("notify-api");authenticate(a);mockMvc.post("/notifications"){contentType=org.springframework.http.MediaType.APPLICATION_JSON;content="{\"type\":\"SYSTEM\",\"message\":\"forged\"}"}.andExpect{status{isMethodNotAllowed()}}}
 private fun user(prefix:String)=users.saveAndFlush(User(username="$prefix-${System.nanoTime()}",email="$prefix-${System.nanoTime()}@example.com",passwordHash="unused",role=Role.STAFF,fullName=prefix,status=EntityStatus.ACTIVE,isActive=true))
 private fun authenticate(u:User){val p=UserPrincipal(u.id!!,u.username,u.role,u.companyId);SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(p,null,listOf(SimpleGrantedAuthority(p.authority)))}
}
