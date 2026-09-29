package com.project.chat_service.model;

import java.util.ArrayList;
import java.util.List;

import com.project.chat_service.constants.MemberRole;

import lombok.*;
import lombok.experimental.SuperBuilder;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder // @Builder 대신 @SuperBuilder 사용
@Table(name="member", schema = "chat",
		indexes = {
				@jakarta.persistence.Index(name = "idx_member_nickname", columnList = "nickname")
		}
		// 닉네임은 유니크가 아니다(V4) — 원본(user-service 의 이름)이 중복을 허용하므로, 복제본에 제약을 걸면 같은
		// 이름으로 바꾼 두 번째 사용자의 변경이 실패한다(docs/48 §7).
		// 이메일 컬럼은 없다(V5) — 신원은 member_id(= 토큰의 uid)다. 복제하지 않는 개인정보는 새지도 않는다(docs/48 §6-⑥).
)
	public class ChatMember implements Persistable<Long> {

		

	@Id
	@Column(name = "member_id")
	private Long id;

	//private String password; // 비밀번호
	private String nickname; // 닉네임
	private String imageUrl; // 프로필 이미지

	@Enumerated(EnumType.STRING)
	private MemberRole role;

	//@Enumerated(EnumType.STRING)
	//private SocialType socialType; // KAKAO, NAVER, GOOGLE

	//private String socialId; // 로그인한 소셜 타입의 식별자 값 (일반 로그인인 경우 null)

	@Builder.Default
	@OneToMany(mappedBy = "member", orphanRemoval = true, cascade = CascadeType.ALL)
	private List<ParticipantChatRoom> roomList =  new ArrayList<ParticipantChatRoom>();

	// ── 왜 Persistable 을 구현하는가 ────────────────────────────────────────
	//
	// member_id 는 user-service 의 userId 를 **그대로 받아 배정**한다(UserMessageListener 가
	// builder().id(createdUser.getUserId()) 로 넣는다). 그래서 save() 시점에 id 가 이미
	// null 이 아니고, SimpleJpaRepository 는 그것만 보고 persist() 대신 merge() 를 고른다.
	//
	//  1. merge 는 붙일 대상을 찾으려 SELECT 를 먼저 던진다 — 회원 가입 이벤트마다 빈손으로
	//     끝나는 SELECT 가 한 번씩 나갔다.
	//
	//  2. 그 리스너는 DataIntegrityViolationException 을 잡아 중복 이벤트를 흘려보낸다.
	//     그런데 merge 는 같은 id 가 이미 있으면 충돌 대신 **UPDATE** 를 내므로 그 catch 는
	//     한 번도 실행되지 않았고, 재전달된 user-created 이벤트가 기존 회원의 nickname·
	//     imageUrl 을 조용히 덮어쓸 수 있었다. persist() 로 가야 의도대로 충돌한다.
	//
	// 플래그를 "저장됐으면 true" 방향으로 둔 이유는 @SuperBuilder 와 @NoArgsConstructor 가
	// 필드 초기화를 어떻게 다루든 기본값 false 가 곧 "새 것"이라 안전하기 때문이다.
	// 불러온 엔티티는 @PostLoad 가 true 로 되돌린다.
	@Transient
	private boolean persisted;

	@Override
	public Long getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return !persisted;
	}

	/** 탈퇴한 회원의 표시 이름. 채팅 기록은 남고 "누구였는지" 만 사라진다. */
	public static final String WITHDRAWN_NICKNAME = com.project.chat_service.service.ChatNotices.WITHDRAWN_NICKNAME;

	/**
	 * 마지막으로 반영한 프로필 변경의 시각(원본 기준, epoch ms). 순서가 뒤집힌 이벤트를 거르는 데만 쓴다 —
	 * 같은 사용자의 이벤트는 한 파티션에 들어가지만, 아웃박스 릴레이가 실패한 행을 나중에 다시 보내면 새 변경보다
	 * 늦게 도착할 수 있다. 탈퇴하면 {@link Long#MAX_VALUE} 로 못 박아 늦게 온 변경이 되살리지 못하게 한다.
	 */
	@Column(name = "profile_updated_at")
	private Long profileUpdatedAt;

	/**
	 * 원본의 표시용 프로필을 따라간다({@code user-updated}, docs/48 §6-⑤). 원본이 이름을 비웠으면 지금 값을 둔다 —
	 * 빈 닉네임은 화면에서 누구인지 알 수 없게 만든다.
	 *
	 * @param updatedAt 원본이 바꾼 시각 — 이미 반영한 것보다 오래됐으면 무시한다
	 * @return 반영했으면 true, 옛 이벤트라 버렸으면 false
	 */
	public boolean applyProfile(String nickname, String imageUrl, long updatedAt) {
		if (profileUpdatedAt != null && updatedAt <= profileUpdatedAt) {
			return false;
		}
		if (nickname != null && !nickname.isBlank()) {
			this.nickname = nickname;
		}
		this.imageUrl = imageUrl;
		this.profileUpdatedAt = updatedAt;
		return true;
	}

	/**
	 * 탈퇴 — 식별정보를 지운다({@code user-deleted}). 행은 남긴다: 채팅방 참여·메시지가 이 id 를 가리킨다.
	 * 이메일은 애초에 없다(V5). 여러 번 불려도 결과가 같다.
	 */
	public void anonymize() {
		this.nickname = WITHDRAWN_NICKNAME;
		this.imageUrl = null;
		this.profileUpdatedAt = Long.MAX_VALUE;
	}

	@PostPersist
	@PostLoad
	private void markPersisted() {
		this.persisted = true;
	}

}
