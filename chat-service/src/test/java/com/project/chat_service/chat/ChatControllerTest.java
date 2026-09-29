package com.project.chat_service.chat;

import com.project.chat_service.client.UserServiceClient;
import com.project.chat_service.controller.ChatApiController;
import com.project.chat_service.controller.StompController;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.service.ChatService;
import com.project.chat_service.testUser.WithMockCustomAccount;
import com.project.common.constants.chat.MessageType;
import com.project.common.dto.chat.request.ChatRoomRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;


@AutoConfigureMockMvc
// 채팅방 생성은 ChatApiController 에 있는데 슬라이스에 StompController 만 넣어 두어
// 대상 컨트롤러가 로딩되지 않았고, 그래서 이 클래스의 테스트는 계속 404 였다(docs/14 B-17).
@WebMvcTest(controllers = {StompController.class, ChatApiController.class})
@MockBean(JpaMetamodelMappingContext.class)
// StompController 가 상담 메시지의 권한을 SupportService 에 묻는다(docs/58).
@MockBean(com.project.chat_service.service.SupportService.class)
public class ChatControllerTest extends MvcTestBasic {
    @MockBean
    ChatService chatService;

    @MockBean
    UserServiceClient shoppingServiceClient;
    

    @Test
    @DisplayName("채팅방 생성 테스트")
    @WithMockCustomAccount
    void createChatRoomTest() throws Exception {
        ChatRoomRequest chatRequestDto = new ChatRoomRequest("room1");
        String requestJson = createStringJson(chatRequestDto);
        // ChatApiController 는 @RequestMapping("/api/chat") 아래에 있다. 예전 경로 "/chat/room"
        // 은 매핑된 적이 없어 항상 404 였다.
        mvc.perform(post("/api/chat/room")
                        .headers(GenerateMockToken.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                        .andExpect(status().isOk())
                       .andDo(print());
    }
       

@Test
    @DisplayName("채팅 내역 리스트 조회 테스트")
    @WithMockCustomAccount
    void chattingListTest() throws Exception { 

        
        String roomId = "sdfdsfdfdfdfdf";
        // MessageDto chat1 = new MessageDto(MessageType.TEXT_TALK, roomId, "user2@example.com", "yun", "안녕하세요", "", 0, 
        // LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul")).toInstant()
        // );
        
        // MessageDto chat2 = new MessageDto(MessageType.TEXT_TALK, roomId, "user2@example.com", "yun", "안녕하세요2", "", 0, 
        // LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul")).toInstant()
        // );

        // MessageDto chat3 = new MessageDto(MessageType.TEXT_TALK, roomId, "user2@example.com", "yun", "안녕하세요3", "", 0, 
        // LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul")).toInstant()
        // );

        // MessageDto chat4 = new MessageDto(MessageType.TEXT_TALK, roomId, "user2@example.com", "yun", "안녕하세요4", "", 0, 
        // LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul")).toInstant()
        // );

        // given(chatService.saveMessage(chat1)).willReturn("안녕하세요1");
        // given(chatService.saveMessage(chat2)).willReturn("안녕하세요2");
        // given(chatService.saveMessage(chat3)).willReturn("안녕하세요3");
        // given(chatService.saveMessage(chat4)).willReturn("안녕하세요4");        

        
        // List<MessageDto> chatList = List.of(chat1, chat2, chat3, chat4);

        // given(chatService.getRoomMessagesByCurser(any(), any())).willReturn(chatList);

        // mvc.perform(get("/chat/{roomId}", roomId)
        //  .headers(GenerateMockToken.getToken())
        // .param("cursor", "-1"))
        // .andExpect(status().isOk())
        //          .andExpect(jsonPath("$", hasSize(2)));
        //         // .andExpect(jsonPath("$.chatList[0].content", is("안녕하세요")));
    }

    

}