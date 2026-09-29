package com.project.common.logging;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 모든 {@link KafkaTemplate} 빈에 <b>프로듀서 쪽 추적</b>을 켠다 (docs/51 §4d).
 *
 * <p>컨슈머 쪽({@code ContainerProperties.setObservationEnabled(true)})은 서비스마다 켜져 있었지만
 * 보내는 쪽은 한 곳도 켜져 있지 않았다. 그러면 메시지에 추적 헤더(traceparent)가 실리지 않아
 * 받는 쪽이 <b>새 추적을 시작한다</b> — 추적 화면(Jaeger)에서 "장바구니 비우기"·"배송 상태"·"구매 확정 알림"
 * 이 그 원인이 된 요청과 따로 떨어져 보였다.
 *
 * <p>템플릿을 만드는 곳이 서비스 여러 곳에 35군데라, 하나씩 고치는 대신 여기서 한 번에 켠다.
 * 레지스트리는 템플릿이 컨텍스트에서 스스로 찾는다(빈이므로). 빈이 아닌 템플릿 — 생성자 안에서
 * {@code new} 로 만드는 것 — 에는 닿지 않으니 그쪽은 레지스트리를 직접 넣는다.
 *
 * <p>⚠️ 아웃박스 릴레이(스케줄러가 보내는 결제 요청 등)는 보내는 순간이 요청 안이 아니라 스케줄러
 * 안이라, 추적은 릴레이에서 새로 시작한다. 원래 요청과 잇자면 추적 문맥을 아웃박스 행에 저장해야
 * 한다 — 이 변경의 범위 밖이다.
 *
 * <p>[JA] すべての {@link KafkaTemplate} Bean で<b>プロデューサー側のトレース</b>を有効にする (docs/51 §4d)。
 *
 * <p>コンシューマー側({@code ContainerProperties.setObservationEnabled(true)})はサービスごとに有効になっていたが、
 * 送信側は一箇所も有効になっていなかった。そのためメッセージにトレースヘッダー(traceparent)が載らず、
 * 受信側が<b>新しいトレースを開始してしまう</b> — トレース画面(Jaeger)で「カートを空にする」・「配送状況」・
 * 「購入確定通知」が、その原因となったリクエストと切り離されて見えていた。
 *
 * <p>テンプレートを作る箇所が複数のサービスに 35 箇所あるので、一つずつ直す代わりにここで一括して有効にする。
 * レジストリはテンプレートがコンテキストから自分で探す(Bean なので)。Bean ではないテンプレート — コンストラクター内で
 * {@code new} で作るもの — には届かないので、そちらはレジストリを直接渡す。
 *
 * <p>⚠️ アウトボックスリレー(スケジューラーが送る決済リクエストなど)は、送信の瞬間がリクエスト内ではなくスケジューラー
 * 内なので、トレースはリレーで新たに始まる。元のリクエストとつなげるにはトレースコンテキストをアウトボックスの行に
 * 保存する必要がある — この変更の範囲外である。
 */
public class KafkaTemplateObservationPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof KafkaTemplate<?, ?> template) {
            template.setObservationEnabled(true);
        }
        return bean;
    }
}
