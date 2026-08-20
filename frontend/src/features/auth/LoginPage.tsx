import * as React from "react"
import { Link, useNavigate } from "react-router-dom"
import { Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { AuthShell, Field } from "./AuthShell"
import { useLogin } from "./useAuthMutations"

export function LoginPage() {
  const navigate = useNavigate()
  const login = useLogin(() => navigate("/", { replace: true }))

  const [email, setEmail] = React.useState("")
  const [password, setPassword] = React.useState("")
  const [touched, setTouched] = React.useState(false)

  const missingFields = email.trim() === "" || password === ""

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (missingFields) return
    login.mutate({ email: email.trim(), password })
  }

  /*
   * ONE message for every 401, whatever the real cause was.
   *
   * auth-service goes to real trouble here — identical 401 bodies for unknown
   * email, wrong password, AND a locked account, plus a dummy BCrypt verify
   * so the paths take the same time (InvalidCredentialsException's own
   * javadoc). Rendering "no account with that email" or "this account is
   * locked" would each hand back an oracle the backend just spent that
   * effort closing, so 401 stays one generic message no matter which of the
   * three it actually was.
   *
   * 429 (TooManyLoginAttemptsException) is the one status safe to call out
   * distinctly — it's a per-username attempt counter that increments the
   * same way for a known and unknown email, so surfacing it confirms only
   * "this username was guessed at repeatedly," not that an account exists.
   * The response carries no Retry-After header or body field to build a
   * countdown from, so the message stays generic about time.
   */
  const formError = login.isError
    ? login.error.status === 401
      ? "That email and password combination is not correct."
      : login.error.status === 429
        ? "Too many sign-in attempts. Please wait a while before trying again."
        : (login.error.message ?? "Something went wrong. Please try again.")
    : null

  return (
    <AuthShell
      title="Sign in"
      description="Access your tickets and upcoming events."
      footer={
        <>
          No account?{" "}
          <Link to="/register" className="font-medium text-foreground underline underline-offset-4">
            Create one
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate>
        <CardContent>
          {/*
            role="alert" so the failure is announced. Without it a screen
            reader user submits, hears nothing, and has no idea the attempt
            failed at all.
          */}
          {formError ? (
            <p
              role="alert"
              className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm font-medium text-destructive"
            >
              {formError}
            </p>
          ) : null}

          <Field id="email" error={touched && !email.trim() ? "Email is required" : undefined}>
            <Label htmlFor="email">Email</Label>
            <Input
              id="email"
              name="email"
              // type=email gets the right mobile keyboard; autoComplete lets
              // the password manager fill both fields as a pair.
              type="email"
              autoComplete="email"
              inputMode="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              aria-invalid={touched && !email.trim()}
              aria-describedby={touched && !email.trim() ? "email-error" : undefined}
            />
          </Field>

          <Field
            id="password"
            error={touched && !password ? "Password is required" : undefined}
          >
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              aria-invalid={touched && !password}
              aria-describedby={touched && !password ? "password-error" : undefined}
            />
          </Field>

          <Button type="submit" size="lg" disabled={login.isPending} className="w-full">
            {login.isPending ? (
              <>
                <Loader2 aria-hidden="true" className="animate-spin" />
                Signing in…
              </>
            ) : (
              "Sign in"
            )}
          </Button>
        </CardContent>
      </form>
    </AuthShell>
  )
}
