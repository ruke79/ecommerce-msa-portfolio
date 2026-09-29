import { useEffect, useRef } from "react"


export function useOnMounted(callbackfunc, dependencies = []) {
  const initialized = useRef(false)

  useEffect(() => {
    if (!initialized.current) {
      initialized.current = true
      callbackfunc()
    }
  }, dependencies)
}


