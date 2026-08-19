import re
import os
import sys

def generate_hand_xml(params):
    # Parse parameters using regex to handle key="value"
    p = {m.group(1): m.group(2) for m in re.finditer(r'(\w+)\s*=\s*"([^"]+)"', params)}
    
    # Identify if it's an hour or minute hand based on the width/height
    config = "hour_idx" if int(p.get('height', 300)) < 280 else "min_idx"
    
    # Matches the verified "Scale-Isolation" logic
    return f"""
            <!-- {p['image'].replace('_hand', '').upper()} -->
            <PartImage x="{p['x']}" y="{p['y']}" width="{p['width']}" height="{p['height']}">
                <Image resource="{p['image']}" /><Variant mode="AMBIENT" target="alpha" value="0" />
                <Transform target="scaleX" value="[CONFIGURATION.{config}] == {p['index']} ? 1 : 0" />
                <Transform target="scaleY" value="[CONFIGURATION.{config}] == {p['index']} ? 1 : 0" />
            </PartImage>
            <PartImage x="{p['x']}" y="{p['y']}" width="{p['width']}" height="{p['height']}" alpha="0">
                <Image resource="{p['ambient']}" /><Variant mode="AMBIENT" target="alpha" value="255" />
                <Transform target="scaleX" value="[CONFIGURATION.{config}] == {p['index']} ? 1 : 0" />
                <Transform target="scaleY" value="[CONFIGURATION.{config}] == {p['index']} ? 1 : 0" />
            </PartImage>"""

def main():
    if len(sys.argv) < 3:
        print("Usage: generate_watchface.py <template_path> <output_path>")
        return

    template_path = sys.argv[1]
    output_path = sys.argv[2]
    
    if not os.path.exists(template_path):
        print(f"Error: {template_path} not found.")
        sys.exit(1)

    with open(template_path, 'r') as f:
        content = f.read()

    # Find all HAND(...) occurrences (even spanning multiple lines)
    pattern = re.compile(r'HAND\s*\((.*?)\)', re.DOTALL)
    result = pattern.sub(lambda m: generate_hand_xml(m.group(1)), content)

    # Create output directory if it doesn't exist
    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    with open(output_path, 'w') as f:
        f.write(result)
        print(f"Success! Generated {output_path}")

if __name__ == "__main__":
    main()
