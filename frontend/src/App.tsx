import { useEffect, useState } from 'react'

type Ping = { status: string; database: string }

function App() {
  const [ping, setPing] = useState<Ping | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetch('/api/ping')
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.json() as Promise<Ping>
      })
      .then(setPing)
      .catch((e: Error) => setError(e.message))
  }, [])

  return (
    <main>
      <h1>CRM</h1>
      {error && <p className="error">Бекенд недоступен: {error}</p>}
      {!error && !ping && <p>Проверяем соединение…</p>}
      {ping && (
        <p>
          Бекенд: {ping.status}
          <br />
          БД: {ping.database}
        </p>
      )}
    </main>
  )
}

export default App
