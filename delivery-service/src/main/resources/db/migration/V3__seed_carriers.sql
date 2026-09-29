-- 택배사 마스터 시드.
--
-- 새 택배사를 붙일 때 필요한 것은 어댑터 클래스 하나와 **이 테이블의 행 하나**뿐이다
-- (docs/25 §4.2). 스케줄러·상태 판정·알림·화면은 손대지 않는다.
--
-- tracking_url 은 고객이 택배사 자체 화면에서 직접 확인할 수 있게 하는 안전판이다 —
-- 우리 추적 데이터가 늦거나 빠질 때 사용자가 스스로 확인할 수 있다.

INSERT INTO delivery.carrier (carrier_code, name_ko, name_en, tracking_url, active, supports_tracking)
VALUES
    ('KOREA_POST', '우체국택배', 'Korea Post',
     'https://service.epost.go.kr/trace.RetrieveDomRigiTraceList.comm?sid1={trackingNumber}',
     true, true),

    -- 개발·테스트용. 실제 택배사 없이 결제→배송→추적→알림 전 경로를 돌려 보기 위한 것이다.
    -- 운영에서는 delivery.carriers.simulated.enabled=false 로 끄고, 이 행도 비활성화한다.
    ('SIMULATED', '테스트배송', 'Simulated Carrier', NULL, true, true),

    -- 아직 어댑터가 없는 택배사들. 송장번호는 보관하고 조회는 고객이 택배사 화면에서 한다.
    -- supports_tracking = false 이므로 폴링 대상이 되지 않는다 — 어댑터 없이 폴링하면
    -- 매 주기마다 실패 로그만 쌓인다.
    ('CJ', 'CJ대한통운', 'CJ Logistics',
     'https://trace.cjlogistics.com/next/tracking.html?wblNo={trackingNumber}', true, false),
    ('HANJIN', '한진택배', 'Hanjin Express',
     'https://www.hanjin.com/kor/CMS/DeliveryMgr/WaybillResult.do?mCode=MN038&schLang=KR&wblnumText2={trackingNumber}',
     true, false),
    ('LOTTE', '롯데택배', 'Lotte Global Logistics',
     'https://www.lotteglogis.com/home/reservation/tracking/linkView?InvNo={trackingNumber}',
     true, false)
ON CONFLICT (carrier_code) DO NOTHING;
