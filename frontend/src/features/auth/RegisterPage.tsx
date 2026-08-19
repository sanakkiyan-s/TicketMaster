import * as React from "react"
import { Link, useNavigate } from "react-router-dom"
import { Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { AuthShell, Field } from "./AuthShell"
import { useRegister } from "./useAuthMutations"

/** Mirrors RegisterRequest's @Size(min = 12, max = 128) exactly. */
const PASSWORD_MIN = 12
const PASSWORD_MAX = 128

export function RegisterPage() {
  const navigate = useNavigate()
  const register = useRegister(() => navigate("/login", { replace: true }))

  const [email, setEmail] = React.useState("")
  const [password, setPassword] = React.useState("")
  const [submitted, setSubmitted] = React.useState(false)

  /*
   * Client validation mirrors the server's rules, and mirrors them ONLY. It
   * exists to save a round trip, never to be the enforcement — anyone can skip
   * it with curl, which is why RegisterRequest carries the same constraints.
   */
  const emailError = !email.trim()
    ? "Email is required"
    : !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())
      ? "Enter a valid email address"
      : undefined

  const passwordError = !password
    ? "Password is required"
    : password.length < PASSWORD_MIN
      ? `Use at least ${PASSWORD_MIN} characters`
      : password.length > PASSWORD_MAX
        ? `Use at most ${PASSWORD_MAX} characters`
        : undefined

  // Server-side field errors (the 400 `errors` map) outrank the local guess.
  const serverFieldErrors = register.isError ? register.error.fieldErrors : {}
  const shownEmailError = serverFieldErrors.email ?? (submitted ? emailError : undefined)
  const shownPasswordError = serverFieldErrors.password ?? (submitted ? passwordError : undefined)

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    if (emailError || passwordError) return
    register.mutate({ email: email.trim(), password })
  }

  const formError =
    register.isError && register.error.status === 409
      ? "An account with that email already exists."
      : register.isError && Object.keys(serverFieldErrors).length === 0
        ? (register.error.message ?? "Something went wrong. Please try again.")
        : null

  return (
    <AuthShell
      title="Create account"
      description="One account for tickets, transfers and resale."
      footer={
        <>
          Already registered?{" "}
          <Link to="/login" className="font-medium text-foreground underline underline-offset-4">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} noValidate>
        <CardContent>
          {formError ? (
            <p
              role="alert"
              className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm font-medium text-destructive"
            >
              {formError}
            </p>
          ) : null}

          <Field id="email" error={shownEmailError}>
            <Label htmlFor="email">Email</Label>
            <Input
              id="email"
              name="email"
              type="email"
              autoComplete="email"
              inputMode="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              aria-invalid={Boolean(shownEmailError)}
              aria-describedby={shownEmailError ? "email-error" : undefined}
            />
          </Field>

          <Field id="password" error={shownPasswordError}>
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              name="password"
              type="password"
              autoComplete="new-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              aria-invalid={Boolean(shownPasswordError)}
              aria-describedby={
                shownPasswordError ? "password-error" : "password-hint"
              }
            />
            {/*
              Persistent hint, not a placeholder. The rule has to stay visible
              while typing — that is exactly when it is needed, and exactly
              when a placeholder is gone.
            */}
            {!shownPasswordError ? (
              <p id="password-hint" className="text-sm text-muted-foreground">
                At least {PASSWORD_MIN} characters.
              </p>
            ) : null}
          </Field>

          <Button type="submit" size="lg" disabled={register.isPending} className="w-full">
            {register.isPending ? (
              <>
                <Loader2 aria-hidden="true" className="animate-spin" />
                Creating account…
              </>
            ) : (
              "Create account"
            )}
          </Button>
        </CardContent>
      </form>
    </AuthShell>
  )
}
