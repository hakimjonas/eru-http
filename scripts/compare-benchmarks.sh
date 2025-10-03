#!/bin/bash

# eru-http Benchmark Comparison Tool
# Compares two benchmark runs and shows performance differences

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check arguments
if [ $# -lt 2 ]; then
    echo "Usage: $0 <baseline-file> <comparison-file>"
    echo ""
    echo "Compare two benchmark results and show performance differences"
    echo ""
    echo "Example:"
    echo "  $0 benchmark-results/benchmark-20251003-200000.txt benchmark-results/benchmark-20251003-210000.txt"
    echo ""
    echo "Or use 'latest' to compare with the most recent run:"
    echo "  $0 benchmark-results/benchmark-20251003-200000.txt latest"
    exit 1
fi

BASELINE=$1
COMPARISON=$2
RESULTS_DIR=${RESULTS_DIR:-./benchmark-results}

# Resolve 'latest' keyword
if [ "$COMPARISON" = "latest" ]; then
    COMPARISON=$(ls -t "$RESULTS_DIR"/benchmark-*.txt 2>/dev/null | head -1)
    if [ -z "$COMPARISON" ]; then
        echo -e "${RED}Error: No benchmark results found in $RESULTS_DIR${NC}"
        exit 1
    fi
    echo "Using latest benchmark: $COMPARISON"
fi

# Check if files exist
if [ ! -f "$BASELINE" ]; then
    echo -e "${RED}Error: Baseline file not found: $BASELINE${NC}"
    exit 1
fi

if [ ! -f "$COMPARISON" ]; then
    echo -e "${RED}Error: Comparison file not found: $COMPARISON${NC}"
    exit 1
fi

echo "========================================="
echo "eru-http Benchmark Comparison"
echo "========================================="
echo ""
echo "Baseline:   $BASELINE"
echo "Comparison: $COMPARISON"
echo ""

# Extract throughput metrics
extract_throughput() {
    local file=$1
    grep "Requests/sec:" "$file" | awk '{print $2}' | sed 's/,//g'
}

# Extract latency metrics
extract_latency() {
    local file=$1
    local metric=$2  # Avg or Max
    grep "Latency" "$file" | awk -v m="$metric" '{
        for(i=1;i<=NF;i++) {
            if ($i ~ /^[0-9.]+[msu]+$/ && i==2) {
                val=$i
                gsub(/[a-z]+/,"",val)
                print val
                break
            }
        }
    }' | head -1
}

# Calculate percentage change
calc_change() {
    local old=$1
    local new=$2
    echo "scale=2; (($new - $old) / $old) * 100" | bc
}

# Format change with color
format_change() {
    local change=$1
    local reverse=${2:-false}  # true if lower is better (like latency)

    if [ "$reverse" = "true" ]; then
        # For latency: lower is better
        if (( $(echo "$change < -5" | bc -l) )); then
            echo -e "${GREEN}${change}%${NC} (improvement)"
        elif (( $(echo "$change > 5" | bc -l) )); then
            echo -e "${RED}${change}%${NC} (regression)"
        else
            echo -e "${YELLOW}${change}%${NC} (similar)"
        fi
    else
        # For throughput: higher is better
        if (( $(echo "$change > 5" | bc -l) )); then
            echo -e "${GREEN}+${change}%${NC} (improvement)"
        elif (( $(echo "$change < -5" | bc -l) )); then
            echo -e "${RED}${change}%${NC} (regression)"
        else
            echo -e "${YELLOW}${change}%${NC} (similar)"
        fi
    fi
}

# Compare throughput
echo "## Throughput Comparison (req/s)"
echo "================================="
echo ""

baseline_throughputs=($(extract_throughput "$BASELINE"))
comparison_throughputs=($(extract_throughput "$COMPARISON"))

scenarios=(
    "Plaintext - Medium"
    "Plaintext - High"
    "JSON - Medium"
    "Large JSON (1KB)"
    "Large Text (10KB)"
    "Large Text (100KB)"
    "Stateful Counter"
    "400 Error"
    "Slow Endpoint"
)

for i in "${!baseline_throughputs[@]}"; do
    if [ $i -lt ${#scenarios[@]} ]; then
        base=${baseline_throughputs[$i]}
        comp=${comparison_throughputs[$i]}

        if [ -n "$base" ] && [ -n "$comp" ]; then
            change=$(calc_change "$base" "$comp")
            echo -n "${scenarios[$i]}: "
            printf "%'d → %'d " "$base" "$comp"
            format_change "$change"
        fi
    fi
done

echo ""
echo "## Latency Comparison (avg)"
echo "============================"
echo ""

echo "(Note: Lower latency is better)"
echo ""

# Latency extraction is trickier - would need more sophisticated parsing
# For now, we show a simplified version

echo "## Summary"
echo "=========="
echo ""

# Calculate overall throughput change
total_base=0
total_comp=0
count=0

for i in "${!baseline_throughputs[@]}"; do
    if [ $i -lt 3 ]; then  # Use first 3 scenarios for average
        base=${baseline_throughputs[$i]}
        comp=${comparison_throughputs[$i]}
        if [ -n "$base" ] && [ -n "$comp" ]; then
            total_base=$(echo "$total_base + $base" | bc)
            total_comp=$(echo "$total_comp + $comp" | bc)
            count=$((count + 1))
        fi
    fi
done

if [ $count -gt 0 ]; then
    avg_base=$(echo "scale=2; $total_base / $count" | bc)
    avg_comp=$(echo "scale=2; $total_comp / $count" | bc)
    overall_change=$(calc_change "$avg_base" "$avg_comp")

    echo -n "Overall Performance: "
    format_change "$overall_change"
    echo ""
fi

echo ""
echo "Full diff:"
echo "  diff -u $BASELINE $COMPARISON"
echo ""
