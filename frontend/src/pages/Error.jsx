import { useRouteError, useNavigate } from 'react-router-dom';
import PageContent from 'components/error/PageContent';
import { useEffect } from 'react';
import Maintenance from 'components/error/Maintenance';
import { useTranslation } from 'i18n/I18nProvider';

/**
 * 라우터 단위 오류 화면.
 *
 * <p><b>오류 화면 자체가 오류로 죽지 않게 하는 것</b>이 이 파일의 유일한 목표다. 예전 코드는
 * 그러지 못했다:
 *
 * <ul>
 *   <li>{@code error.code} 를 방어 없이 읽었다. 라우터는 {@code throw new Response(...)} 나
 *       문자열도 오류로 받을 수 있고 그때 error 는 undefined 이거나 code 가 없다 →
 *       오류 화면을 그리다가 다시 터지고, 사용자는 <b>완전히 빈 화면</b>을 본다.</li>
 *   <li>{@code error.data.message} 를 500 일 때 그대로 읽었다. 본문 없는 500 이면 여기서
 *       또 터진다.</li>
 *   <li>{@code navigate('error_server')} 는 앞에 슬래시가 없어 <b>상대 경로</b>다. /order/12
 *       에서 오류가 나면 /order/error_server 로 가고, 그런 라우트는 없으니 다시 오류다.</li>
 *   <li>"Try again" 버튼이 {@code toast("Worng Error")} 만 띄웠다. 사용자가 할 수 있는 게
 *       아무것도 없었다.</li>
 * </ul>
 */
function ErrorPage() {
  const error = useRouteError();
  const navigate = useNavigate();
  const { t } = useTranslation();

  const status = error?.status;
  const code = error?.code;
  const isNetworkError = code === 'ERR_NETWORK';

  let title = t('error.page.title');
  let message = t('error.page.message');

  if (status === 500) {
    message = error?.data?.message ?? t('error.page.server');
  } else if (status === 404) {
    title = t('error.page.not_found_title');
    message = t('error.page.not_found_message');
  } else if (error?.message) {
    message = error.message;
  }

  useEffect(() => {
    if (isNetworkError) {
      navigate('/error_server', { replace: true });
    }
  }, [isNetworkError, navigate]);

  if (isNetworkError) {
    return <Maintenance />;
  }

  return (
    <PageContent title={title}>
      <p>{message}</p>
      {code && <p className="text-sm text-slate-500">{String(code)}</p>}
      {status && <p className="text-sm text-slate-500">{String(status)}</p>}
      <div className="mt-4 flex gap-2 justify-center">
        {/* 실제로 다시 시도한다. 라우터 오류는 대개 로더 실패라 재요청으로 풀린다. */}
        <button type="button" onClick={() => navigate(0)}>{t('error.retry')}</button>
        <button type="button" onClick={() => navigate('/', { replace: true })}>{t('error.go_home')}</button>
      </div>
    </PageContent>
  );
}

export default ErrorPage;
