#!/usr/bin/env bash
# End-to-end check of a running CloudShop over HTTP, driving the same forms a
# browser would: CSRF tokens, session cookies, redirects and all.
#
#   ./docs/deploy/smoke-test.sh <base-url>
#   ./docs/deploy/smoke-test.sh https://dxxxxxxxxxxxxx.cloudfront.net
#   ./docs/deploy/smoke-test.sh http://localhost:8080
#
# What it does, in order:
#   1. checks /health and /actuator/health
#   2. registers a throwaway buyer (smoke_<timestamp>) and signs in as them
#   3. buys one unit of whichever product has the most stock
#   4. confirms the purchase is in the buyer's history and the stock dropped by one
#   5. confirms the buyer is refused at /owner/sales
#   6. signs in as the owner, raises that product's price by one cent,
#      confirms the catalog shows the new price, then puts the old price back
#   7. confirms the sale is in the owner's sales history at the price actually paid
#
# What it leaves behind: one buyer account and one purchase, so one product has one
# unit less stock. The price is restored.
#
# Owner credentials are never typed here or printed. They are read from the
# OWNER_USERNAME and OWNER_PASSWORD environment variables if both are set, and
# otherwise from the secrets file written at deployment time
# (~/.cloudshop/aws-secrets.env, or the path in SECRETS_FILE).
#
# Needs bash, curl, awk, sed and grep - Git Bash on Windows has them all.

set -u

BASE="${1:-}"
if [ -z "$BASE" ]; then
    echo "usage: $0 <base-url>     for example: $0 https://dxxxxxxxxxxxxx.cloudfront.net" >&2
    exit 2
fi
BASE="${BASE%/}"

SECRETS_FILE="${SECRETS_FILE:-$HOME/.cloudshop/aws-secrets.env}"
case "$SECRETS_FILE" in /*|[A-Za-z]:*) ;; *) SECRETS_FILE="$PWD/$SECRETS_FILE" ;; esac

# Everything temporary lives in one private directory, and the script works from
# inside it so that files can be named without a path. Git Bash rewrites Unix paths
# handed to curl, but not one embedded in "password@/some/path", which is the form
# curl needs to read a password from a file instead of from its command line.
TMP="$(mktemp -d)"
chmod 700 "$TMP"
trap 'cd /; rm -rf "$TMP"' EXIT
cd "$TMP" || exit 1

PASSED=0
FAILED=0
ok()   { PASSED=$((PASSED + 1)); echo "  ok    $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL  $1"; }
# check "<description>" "<actual>" "<expected glob pattern>"
check() {
    # shellcheck disable=SC2254
    case "$2" in
        $3) ok "$1" ;;
        *)  fail "$1 (expected: $3, got: $2)" ;;
    esac
}
stop() { echo; echo "Stopped: $1"; summary; exit 1; }
summary() { echo; echo "$PASSED passed, $FAILED failed  -  $BASE"; }

# --- HTTP helpers -------------------------------------------------------------
# Each actor (buyer, owner) has its own cookie jar, like its own browser.
# Every call prints "<status> <redirect target>" and leaves the response in ./body.

get() {  # get <jar> <path>
    curl -sS --max-time 30 -b "$1.jar" -c "$1.jar" -o body \
         -w '%{http_code} %{redirect_url}' "$BASE$2"
}
post() { # post <jar> <path> [curl data arguments...]
    local jar="$1" path="$2"; shift 2
    curl -sS --max-time 30 -b "$jar.jar" -c "$jar.jar" -o body \
         -w '%{http_code} %{redirect_url}' "$@" "$BASE$path"
}
# The CSRF token Thymeleaf wrote into the form on the page just fetched.
csrf() { grep -o 'name="_csrf" value="[^"]*"' body | head -1 | sed 's/.*value="\([^"]*\)"/\1/'; }
# The value="..." of a named form field on the page just fetched.
field() { grep -o "name=\"$1\" value=\"[^\"]*\"" body | head -1 | sed 's/.*value="\([^"]*\)"/\1/' | unescape; }
unescape() { sed -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&quot;/"/g' -e "s/&#39;/'/g" -e 's/&amp;/\&/g'; }
body_has() { grep -qF -- "$1" body; }
stock_on_page() { grep -o '[0-9][0-9]* in stock' body | head -1 | grep -o '^[0-9]*'; }
price_on_page() { grep -o '\$[0-9][0-9,]*\.[0-9][0-9]' body | head -1; }

sign_in() { # sign_in <jar> <username> <password file>
    get "$1" /login > /dev/null
    post "$1" /login --data-urlencode "username=$2" --data-urlencode "password@$3" \
         --data-urlencode "_csrf=$(csrf)"
}
sign_out() { # sign_out <jar>   (needs a page with a form to have been fetched just before)
    get "$1" /products > /dev/null
    post "$1" /logout --data-urlencode "_csrf=$(csrf)" > /dev/null
}

echo "CloudShop smoke test against $BASE"

# --- 1. Health ----------------------------------------------------------------
echo
echo "1. Health"
check "/health answers 200" "$(get anon /health)" "200 *"
body_has '"status":"UP"' && ok "/health reports UP" || fail "/health reports UP"
check "/actuator/health answers 200" "$(get anon /actuator/health)" "200 *"
body_has '"status":"UP"' && ok "/actuator/health reports UP, so the database is reachable" \
                         || fail "/actuator/health reports UP"
body_has '"components"' && fail "/actuator/health hides component details" \
                        || ok "/actuator/health hides component details"

# --- 2. Catalog: pick the product with the most stock ---------------------------
echo
echo "2. Catalog"
check "/products answers 200" "$(get anon /products)" "200 *"
IDS="$(grep -o 'href="/products/[0-9]*"' body | grep -o '[0-9][0-9]*' | sort -un)"
[ -n "$IDS" ] || stop "the catalog lists no products"
ok "the catalog lists $(echo "$IDS" | wc -l | tr -d ' ') product(s)"

PRODUCT_ID=""; STOCK_BEFORE=0
for id in $IDS; do
    get anon "/products/$id" > /dev/null
    s="$(stock_on_page)"; s="${s:-0}"
    if [ "$s" -gt "$STOCK_BEFORE" ]; then PRODUCT_ID="$id"; STOCK_BEFORE="$s"; fi
done
[ -n "$PRODUCT_ID" ] || stop "every product is out of stock, so there is nothing to buy"
get anon "/products/$PRODUCT_ID" > /dev/null
PRODUCT_NAME="$(sed -n 's/.*<h1 class="h4 mb-0">\(.*\)<\/h1>.*/\1/p' body | head -1 | unescape)"
PRICE_SHOWN="$(price_on_page)"
ok "chose product $PRODUCT_ID: $PRODUCT_NAME, $PRICE_SHOWN, $STOCK_BEFORE in stock"
body_has 'name="quantity"' && fail "an anonymous visitor is not offered the purchase form" \
                           || ok "an anonymous visitor is not offered the purchase form"
check "anonymous /owner/sales is sent to sign in" "$(get anon /owner/sales)" "302 */login"

# --- 3. Register a buyer and sign in --------------------------------------------
echo
echo "3. Buyer registration and sign-in"
BUYER="smoke_$(date +%s)"
LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 20 > buyer.pw

get buyer /register > /dev/null
check "registering $BUYER redirects to sign in" \
      "$(post buyer /register --data-urlencode "username=$BUYER" \
              --data-urlencode "email=$BUYER@example.com" \
              --data-urlencode "password@buyer.pw" \
              --data-urlencode "confirmPassword@buyer.pw" \
              --data-urlencode "_csrf=$(csrf)")" "302 */login?registered"

check "a wrong password is refused" \
      "$(printf 'not-the-password' > wrong.pw; sign_in wrong "$BUYER" wrong.pw)" "302 */login?error"
check "the buyer signs in and lands on the catalog" \
      "$(sign_in buyer "$BUYER" buyer.pw)" "302 */products"

# --- 4. Purchase ----------------------------------------------------------------
echo
echo "4. Purchase"
check "the product page loads for the buyer" "$(get buyer "/products/$PRODUCT_ID")" "200 *"
body_has 'name="quantity"' && ok "the buyer is offered the purchase form" \
                           || fail "the buyer is offered the purchase form"
check "buying 1 x $PRODUCT_NAME redirects to the purchase history" \
      "$(post buyer "/products/$PRODUCT_ID/purchase" --data-urlencode "quantity=1" \
              --data-urlencode "_csrf=$(csrf)")" "302 */my-purchases"

check "/my-purchases answers 200" "$(get buyer /my-purchases)" "200 *"
body_has "Purchase confirmed: 1 x" && ok "the confirmation banner is shown" \
                                   || fail "the confirmation banner is shown"
body_has "$PRICE_SHOWN" && ok "the history shows the purchase at $PRICE_SHOWN" \
                        || fail "the history shows the purchase at $PRICE_SHOWN"

get anon "/products/$PRODUCT_ID" > /dev/null
STOCK_AFTER="$(stock_on_page)"; STOCK_AFTER="${STOCK_AFTER:-0}"
check "stock dropped from $STOCK_BEFORE to $((STOCK_BEFORE - 1))" "$STOCK_AFTER" "$((STOCK_BEFORE - 1))"

check "asking for more than is in stock is refused" \
      "$(get buyer "/products/$PRODUCT_ID" > /dev/null
         post buyer "/products/$PRODUCT_ID/purchase" --data-urlencode "quantity=$((STOCK_AFTER + 1000))" \
              --data-urlencode "_csrf=$(csrf)")" "302 */products/$PRODUCT_ID"
get buyer "/products/$PRODUCT_ID" > /dev/null
body_has "Nothing was purchased." && ok "the buyer is told why, and nothing was bought" \
                                  || fail "the buyer is told why, and nothing was bought"

# --- 5. A buyer is not an owner -------------------------------------------------
echo
echo "5. Access control"
check "the buyer is refused at /owner/sales" "$(get buyer /owner/sales)" "403 *"
check "the buyer is refused at /owner/products" "$(get buyer /owner/products)" "403 *"
check "a POST without a CSRF token is refused" \
      "$(post buyer "/products/$PRODUCT_ID/purchase" --data-urlencode "quantity=1")" "403 *"
sign_out buyer

# --- 6. Owner: change a price ---------------------------------------------------
echo
echo "6. Owner sign-in and price change"
if [ -n "${OWNER_USERNAME:-}" ] && [ -n "${OWNER_PASSWORD:-}" ]; then
    OWNER="$OWNER_USERNAME"
    printf '%s' "$OWNER_PASSWORD" > owner.pw
elif [ -r "$SECRETS_FILE" ]; then
    OWNER="$(sed -n 's/^OWNER_USERNAME=//p' "$SECRETS_FILE" | tr -d '\r\n')"
    sed -n 's/^OWNER_PASSWORD=//p' "$SECRETS_FILE" | tr -d '\r\n' > owner.pw
else
    stop "no owner credentials: set OWNER_USERNAME and OWNER_PASSWORD, or point SECRETS_FILE at the secrets file"
fi
[ -n "$OWNER" ] && [ -s owner.pw ] || stop "the owner username or password is empty"

check "the owner signs in and lands on the inventory" \
      "$(sign_in owner "$OWNER" owner.pw)" "302 */owner/products"
check "the inventory answers 200" "$(get owner /owner/products)" "200 *"

check "the edit form loads" "$(get owner "/owner/products/$PRODUCT_ID/edit")" "200 *"
P_NAME="$(field name)"
P_PRICE="$(field price)"
P_STOCK="$(field stock)"
P_DESC="$(sed -n 's/.*name="description">\(.*\)<\/textarea>.*/\1/p' body | head -1 | unescape)"
[ -n "$P_NAME" ] && [ -n "$P_PRICE" ] && [ -n "$P_STOCK" ] && [ -n "$P_DESC" ] \
    || stop "could not read the product's current values from the edit form"
NEW_PRICE="$(awk -v p="$P_PRICE" 'BEGIN { printf "%.2f", p + 0.01 }')"

save_product() { # save_product <price>
    post owner "/owner/products/$PRODUCT_ID" --data-urlencode "name=$P_NAME" \
         --data-urlencode "description=$P_DESC" --data-urlencode "price=$1" \
         --data-urlencode "stock=$P_STOCK" --data-urlencode "_csrf=$(csrf)"
}
check "changing the price from $P_PRICE to $NEW_PRICE is saved" "$(save_product "$NEW_PRICE")" "302 */owner/products"
get anon "/products/$PRODUCT_ID" > /dev/null
check "the catalog now shows the new price" "$(price_on_page | tr -d '$,')" "$NEW_PRICE"

# --- 7. Owner: sales history ----------------------------------------------------
echo
echo "7. Sales history"
check "the sales report answers 200" "$(get owner "/owner/sales?productId=$PRODUCT_ID")" "200 *"
body_has "<td>$BUYER</td>" && ok "the sale to $BUYER is listed" || fail "the sale to $BUYER is listed"
grep -A4 -F "<td>$BUYER</td>" body | grep -qF -- "$PRICE_SHOWN" \
    && ok "it is listed at $PRICE_SHOWN, the price paid, not the new price" \
    || fail "it is listed at $PRICE_SHOWN, the price paid, not the new price"
body_has "Revenue total" && ok "the report shows a revenue total" || fail "the report shows a revenue total"

# Put the price back, so the catalog is as it was.
get owner "/owner/products/$PRODUCT_ID/edit" > /dev/null
check "restoring the price to $P_PRICE is saved" "$(save_product "$P_PRICE")" "302 */owner/products"
get anon "/products/$PRODUCT_ID" > /dev/null
check "the catalog shows the original price again" "$(price_on_page)" "$PRICE_SHOWN"

check "the owner cannot purchase" \
      "$(get owner "/products/$PRODUCT_ID" > /dev/null
         post owner "/products/$PRODUCT_ID/purchase" --data-urlencode "quantity=1" \
              --data-urlencode "_csrf=$(csrf)")" "403 *"
sign_out owner
check "after signing out, /owner/products asks for sign-in again" "$(get owner /owner/products)" "302 */login"

summary
[ "$FAILED" -eq 0 ]
