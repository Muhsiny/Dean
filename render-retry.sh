# Beheshti University Render build recovery
# Sourced by non-interactive bash through BASH_ENV.
if [ -z "${__BU_RETRY_GIT_LOADED:-}" ]; then
  export __BU_RETRY_GIT_LOADED=1
  git() {
    if [ "${1:-}" = "clone" ]; then
      case " $* " in
        *"ogq4q532.basicdeploy.com/beheshti-university-production.git"*)
          local dest="${@: -1}"
          local n=1
          while [ "$n" -le 30 ]; do
            rm -rf "$dest"
            echo "[BU recovery] BasicDeploy clone attempt $n/30"
            if /usr/bin/git "$@"; then
              return 0
            fi
            n=$((n+1))
            sleep 3
          done
          return 1
          ;;
      esac
    fi
    /usr/bin/git "$@"
  }
fi
