# runs inside the Ubuntu environment (proot) - installs .NET 10 SDK and builds UndertaleModCli from source
set -e
export DEBIAN_FRONTEND=noninteractive
export DOTNET_ROOT=/opt/dotnet
export PATH=$PATH:/opt/dotnet
export DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1
export DOTNET_CLI_TELEMETRY_OPTOUT=1
apt-get update -y
apt-get install -y curl git unzip ca-certificates xdelta3
if [ ! -x /opt/dotnet/dotnet ]; then
  curl -sSL https://dot.net/v1/dotnet-install.sh -o /tmp/dotnet-install.sh
  bash /tmp/dotnet-install.sh --channel 10.0 --install-dir /opt/dotnet
fi
dotnet --version
if [ ! -f /opt/utmt/UndertaleModCli.dll ]; then
  rm -rf /tmp/utmt-src
  git clone --depth 1 --recurse-submodules --shallow-submodules https://github.com/UnderminersTeam/UndertaleModTool /tmp/utmt-src
  cd /tmp/utmt-src
  dotnet publish UndertaleModCli -c Release -p:PublishSingleFile=false --self-contained false -o /opt/utmt
fi
ls /opt/utmt/UndertaleModCli.dll
echo "UndertaleModCli ready."
