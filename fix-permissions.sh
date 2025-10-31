#!/bin/bash

# Script para liberar permissões totais para containers Docker
# Permite que containers escrevam livremente nas pastas do projeto

# Detectar automaticamente o diretório do script
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Detectar usuário atual
CURRENT_USER=$(whoami)

echo "🔓 Liberando permissões para containers Docker..."
echo "📁 Diretório: $PROJECT_DIR"
echo "👤 Usuário: $CURRENT_USER"
echo ""

# Liberar pasta principal
echo "✓ Liberando pasta principal..."
sudo chmod -R 777 "$PROJECT_DIR"

# Liberar pasta output especificamente
if [ -d "$PROJECT_DIR/output" ]; then
    echo "✓ Liberando pasta output..."
    sudo chmod -R 777 "$PROJECT_DIR/output"
fi

# Liberar pasta tmp (usada pelos containers)
echo "✓ Liberando /tmp (usada pelos containers)..."
sudo chmod 1777 /tmp

# Definir permissões padrão para novos arquivos (ACL)
echo "✓ Configurando ACL para novos arquivos..."
sudo setfacl -R -m u::rwx,g::rwx,o::rwx "$PROJECT_DIR" 2>/dev/null || echo "⚠️  ACL não disponível (opcional)"
sudo setfacl -R -d -m u::rwx,g::rwx,o::rwx "$PROJECT_DIR" 2>/dev/null || echo "⚠️  ACL padrão não configurado (opcional)"

# Garantir que o usuário atual seja dono
echo "✓ Ajustando proprietário para $CURRENT_USER..."
sudo chown -R $CURRENT_USER:$CURRENT_USER "$PROJECT_DIR"

# Mostrar permissões atuais
echo ""
echo "📊 Permissões atuais:"
ls -lah "$PROJECT_DIR" | head -n 15

echo ""
echo "✅ Permissões liberadas com sucesso!"
echo ""
echo "Permissões aplicadas:"
echo "  - Leitura: ✓"
echo "  - Escrita: ✓"
echo "  - Execução: ✓"
echo "  - Recursivo: ✓"
echo ""
echo "Os containers agora têm acesso total para:"
echo "  • Criar arquivos e pastas"
echo "  • Modificar arquivos existentes"
echo "  • Deletar arquivos"
echo "  • Executar scripts"
