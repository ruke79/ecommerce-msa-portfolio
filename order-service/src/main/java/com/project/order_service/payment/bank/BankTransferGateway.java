package com.project.order_service.payment.bank;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.project.common.constants.PaymentProvider;
import com.project.order_service.payment.PaymentGateway;
import com.project.order_service.payment.PaymentVerificationException;
import com.project.order_service.payment.RefundResult;
import com.project.order_service.payment.RefundingGateway;
import com.project.order_service.payment.VerifiedPayment;

import lombok.extern.slf4j.Slf4j;

/**
 * 무통장입금 — 물어볼 제공자가 없는 "게이트웨이".
 *
 * <p>결제 확인은 {@code recordPayment} 가 아니라 {@code BankTransferService.confirmDeposit}
 * (관리자)가 한다. 그래서 {@link #verify} 는 <b>부르면 안 되는</b> 메서드다 — 불렸다는 것은
 * 누군가 무통장입금을 카드처럼 "브라우저가 결제했다고 말하는" 경로로 넣었다는 뜻이라
 * 거부한다.
 *
 * <p>환불은 관리자가 계좌로 송금한 <b>기록</b>이다. 돈을 옮기는 API 가 없으니 언제나
 * "받아들여졌다" 고 답하고, 참조는 환불 키에서 만든다. 그래서 {@code refundOrder} 는 이
 * 제공자에 한해 관리자만 허용한다 — 고객이 스스로 부르면 돈이 가지 않았는데 주문만
 * REFUNDED 가 된다.
 *
 * <p>빈은 언제나 있다(자격증명이 필요 없다). 켜고 끄는 것은 {@code bank-transfer.enabled}
 * 이고, 꺼져 있으면 입금 안내 시작이 거부된다.
 *
 * <p>[JA] 銀行振込 — 問い合わせるプロバイダのない「ゲートウェイ」。
 * 決済確認は {@code recordPayment} ではなく {@code BankTransferService.confirmDeposit}
 * (管理者)が行う。そのため {@link #verify} は<b>呼んではならない</b>メソッドである — 呼ばれたということは、
 * 誰かが銀行振込をカードのように「ブラウザが決済したと言う」経路に入れたという意味なので拒否する。
 * 返金は管理者が口座へ送金した<b>記録</b>である。お金を動かす API がないので常に
 * 「受け付けた」と答え、参照は返金キーから作る。そのため {@code refundOrder} はこの
 * プロバイダに限り管理者だけを許可する — 顧客が自分で呼ぶと、お金は送られていないのに注文だけが
 * REFUNDED になる。
 * Bean は常にある(認証情報が不要)。有効・無効を切り替えるのは {@code bank-transfer.enabled}
 * であり、無効なら入金案内の開始が拒否される。
 */
@Slf4j
@Component
public class BankTransferGateway implements PaymentGateway, RefundingGateway {

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.BANK_TRANSFER;
    }

    @Override
    public VerifiedPayment verify(String providerPaymentId) {
        throw new PaymentVerificationException(
                "무통장입금은 관리자의 입금 확인으로 반영됩니다 — 브라우저가 확인을 요청할 수 없습니다.");
    }

    @Override
    public RefundResult refund(String providerPaymentId, BigDecimal amount, String currency, String reason,
            String refundKey) {
        String ref = "bank-refund-" + (refundKey == null || refundKey.isBlank() ? providerPaymentId : refundKey);
        log.info("무통장입금 환불 기록: paymentRef={} amount={} {} ref={}", providerPaymentId, amount, currency, ref);
        return new RefundResult(PaymentProvider.BANK_TRANSFER, ref, true, "manual", amount, currency);
    }
}
