import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  getMyProfile,
  updateMyProfile,
  uploadMyProfileImage,
  deleteMyProfileImage,
} from "util/api";

/** 프로필 쿼리 키. 수정 후 이 키를 갱신하면 화면 전체가 한 번에 따라온다. */
export const PROFILE_KEY = ["profile", "me"];

/**
 * 내 프로필.
 *
 * <p>🐛 예전에는 화면들이 `useAuthContext().user` 를 프로필처럼 썼다. 그 값은 JWT 에서
 * 만든 `{ email, userId, roles }` 뿐이라 `user.name` · `user.image` 는 언제나 undefined
 * 였고, 프로필 사이드바는 이름이 빈 칸에 아바타가 깨진 채로 나왔다.
 *
 * <p>JWT 는 신원 증명이지 프로필 저장소가 아니다 — 이름을 바꿔도 이미 발급된 토큰은
 * 그대로다. 프로필은 서버에서 읽는다.
 *
 * <p>`throwOnError` 를 끈다. 전역 QueryClient 는 true 라(util/api.js) 프로필을 못 받으면
 * 예외가 ErrorBoundary 까지 올라가 <b>프로필 화면 전체가 사라진다.</b> 이름과 사진이
 * 비는 것과 화면이 죽는 것은 다르다 — 주소·주문 탭은 그대로 쓸 수 있어야 한다.
 */
export function useProfile({ enabled = true } = {}) {
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: PROFILE_KEY,
    queryFn: getMyProfile,
    enabled,
    // 프로필은 거의 바뀌지 않는다. 바뀌는 순간(수정·업로드)은 아래 훅들이 직접 갱신한다.
    staleTime: 5 * 60 * 1000,
    throwOnError: false,
  });

  return { profile: data, isPending, isError, error, refetch };
}

/**
 * 프로필 수정·이미지 업로드·이미지 삭제.
 *
 * <p>세 가지 모두 서버가 <b>갱신된 프로필 전체</b>를 돌려준다. 그것을 그대로 캐시에
 * 넣으면 다시 조회하지 않아도 화면이 최신이 된다(`setQueryData`).
 *
 * <p>응답을 버리고 `invalidateQueries` 만 하면 왕복이 한 번 더 생기고, 그 사이에 옛 값이
 * 잠깐 보인다 — 방금 바꾼 이름이 돌아왔다가 다시 바뀌는 깜빡임이다.
 */
export function useUpdateProfile() {
  const queryClient = useQueryClient();

  const onSuccess = (updated) => {
    if (updated) {
      queryClient.setQueryData(PROFILE_KEY, updated);
    }
  };

  const save = useMutation({
    mutationFn: updateMyProfile,
    onSuccess,
    throwOnError: false,
  });

  const uploadImage = useMutation({
    mutationFn: uploadMyProfileImage,
    onSuccess,
    throwOnError: false,
  });

  const removeImage = useMutation({
    mutationFn: deleteMyProfileImage,
    onSuccess,
    throwOnError: false,
  });

  return { save, uploadImage, removeImage };
}

/** 서버가 준 메시지를 우선 보여준다 — "실패했습니다" 보다 이유가 있는 편이 낫다. */
export function profileErrorText(error, fallback) {
  return error?.response?.data?.message ?? fallback;
}
