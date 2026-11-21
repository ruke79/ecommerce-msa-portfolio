package com.project.chat_service.chat;

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
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;


@AutoConfigureMockMvc
// 채팅방 생성은 ChatApiController 에 있는데 슬라이스에 StompController 만 넣어 두어
// 대상 컨트롤러가 로딩되지 않았고, 그래서 이 클래스의 테스트는 계속 404 였다(docs/14 B-17).
// [JA] チャットルーム作成は ChatApiController にあるのにスライスに StompController だけを入れていたため
//      対象コントローラが読み込まれず、このクラスのテストはずっと 404 だった(docs/14 B-17)。
@WebMvcTest(controllers = {StompController.class, ChatApiController.class})
@MockBean(JpaMetamodelMappingContext.class)
// StompController 가 상담 메시지의 권한을 SupportService 에 묻는다(docs/58).
// [JA] StompController が相談メッセージの権限を SupportService に問い合わせる(docs/58)。
@MockBean(com.project.chat_service.service.SupportService.class)
public class ChatControllerTest extends MvcTestBasic {
    @MockBean
    ChatService chatService;

    

    @Test
    @DisplayName("채팅방 생성 테스트")
    @WithMockCustomAccount
    void createChatRoomTest() throws Exception {
        ChatRoomRequest chatRequestDto = new ChatRoomRequest("room1");
        String requestJson = createStringJson(chatRequestDto);
        // ChatApiController 는 @RequestMapping("/api/chat") 아래에 있다. 예전 경로 "/chat/room"
        // 은 매핑된 적이 없어 항상 404 였다.
        // [JA] ChatApiController は @RequestMapping("/api/chat") の下にある。以前のパス "/chat/room"
        //      はマッピングされたことがなく、常に 404 だった。
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
        // [JA] (コメントアウトされたコード) 本文の値は「こんにちは」
        
        // MessageDto chat2 = new MessageDto(MessageType.TEXT_TALK, roomId, "user2@example.com", "yun", "안녕하세요2", "", 0, 
        // LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul")).toInstant()
        // );
        // [JA] (コメントアウトされたコード) 本文の値は「こんにちは2」

        // MessageDto chat3 = new MessageDto(MessageType.TEXT_TALK, roomId, "user2@example.com", "yun", "안녕하세요3", "", 0, 
        // LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul")).toInstant()
        // );
        // [JA] (コメントアウトされたコード) 本文の値は「こんにちは3」

        // MessageDto chat4 = new MessageDto(MessageType.TEXT_TALK, roomId, "user2@example.com", "yun", "안녕하세요4", "", 0, 
        // LocalDateTime.now().atZone(ZoneId.of("Asia/Seoul")).toInstant()
        // );
        // [JA] (コメントアウトされたコード) 本文の値は「こんにちは4」

        // given(chatService.saveMessage(chat1)).willReturn("안녕하세요1");
        // given(chatService.saveMessage(chat2)).willReturn("안녕하세요2");
        // given(chatService.saveMessage(chat3)).willReturn("안녕하세요3");
        // given(chatService.saveMessage(chat4)).willReturn("안녕하세요4");        
        // [JA] (コメントアウトされたコード) 戻り値は「こんにちは1」〜「こんにちは4」

        
        // List<MessageDto> chatList = List.of(chat1, chat2, chat3, chat4);

        // given(chatService.getRoomMessagesByCurser(any(), any())).willReturn(chatList);

        // mvc.perform(get("/chat/{roomId}", roomId)
        //  .headers(GenerateMockToken.getToken())
        // .param("cursor", "-1"))
        // .andExpect(status().isOk())
        //          .andExpect(jsonPath("$", hasSize(2)));
        //         // .andExpect(jsonPath("$.chatList[0].content", is("안녕하세요")));
        // [JA] (コメントアウトされたコード) 期待する content は「こんにちは」
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.project.chat_service.common.util.TokenHandler tokenHandler;

    /**
     * 🔒 공개 채팅방 모델 — GET /api/chat/rooms 가 모든 인증 사용자에게 방 목록을 주고 GET /api/chat/room/{id} 가 참여 확인 없이
     * 기록을 준다(의도된 공개 단체 채팅). 그런데 1:1 상담(support-<번호>)은 본인 · 상담원만 봐야 한다 — 그 기록이 이 공개 경로로
     * 새지 않도록 컨트롤러가 support- 방을 404 로 막는다(본인 · 상담원 경로는 /api/chat/support/threads/{id}, docs/58). 보안 점검
     * (2026-11-21)에서 이 경계를 회귀 시험으로 고정한다 — 막는 줄을 지우면 상담 기록이 아무 로그인 사용자에게 열린다.
     * [JA] 公開グループチャットは意図的。だが 1:1 サポート(support-)は本人・担当者だけ — この公開経路で漏れないよう 404 で塞ぐ。
     */
    @Test
    @DisplayName("🔴 상담(support-) 방 기록은 공개 방 경로로 읽을 수 없다 — 404, 서비스도 부르지 않는다")
    @WithMockCustomAccount
    void supportRoomNotReadableViaPublicRoomPath() throws Exception {
        given(tokenHandler.resolveVerifiedUserId(any())).willReturn(1L);

        mvc.perform(get("/api/chat/room/{roomId}", "support-5")
                        .headers(GenerateMockToken.getToken())
                        .param("cursor", "-1"))
                .andExpect(status().isNotFound());

        then(chatService).should(never()).getRoomMessagesByCurser(any(), any());
    }

    @Test
    @DisplayName("보통 방은 공개 방 경로로 읽힌다 — 200(위 404 가 상담 방에만 적용됨을 보인다)")
    @WithMockCustomAccount
    void normalRoomReadableViaPublicRoomPath() throws Exception {
        given(tokenHandler.resolveVerifiedUserId(any())).willReturn(1L);
        given(chatService.getRoomMessagesByCurser(eq("room-xyz"), any())).willReturn(List.of());

        mvc.perform(get("/api/chat/room/{roomId}", "room-xyz")
                        .headers(GenerateMockToken.getToken())
                        .param("cursor", "-1"))
                .andExpect(status().isOk());
    }

}