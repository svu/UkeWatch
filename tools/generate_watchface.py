import xml.etree.ElementTree as ET
import os
import sys

def create_hand_elements(hand_elem):
    # Get attributes from the <HAND /> tag
    hand_type = hand_elem.get('hand_type', 'minute')
    image = hand_elem.get('image')
    ambient = hand_elem.get('ambient')
    if not ambient and image:
        ambient = image.replace('_hand', '_ambient_hand')
    x = hand_elem.get('x')
    y = hand_elem.get('y')
    width = hand_elem.get('width')
    height = hand_elem.get('height')
    index = hand_elem.get('index')
    
    config = "hour_idx" if hand_type == "hour" else "min_idx"
    
    # Create the Active PartImage
    active_part = ET.Element('PartImage', x=x, y=y, width=width, height=height)
    ET.SubElement(active_part, 'Image', resource=image)
    ET.SubElement(active_part, 'Variant', mode="AMBIENT", target="alpha", value="0")
    ET.SubElement(active_part, 'Transform', target="scaleX", value=f"[CONFIGURATION.{config}] == {index} ? 1 : 0")
    ET.SubElement(active_part, 'Transform', target="scaleY", value=f"[CONFIGURATION.{config}] == {index} ? 1 : 0")
    
    # Create the Ambient PartImage
    ambient_part = ET.Element('PartImage', x=x, y=y, width=width, height=height, alpha="0")
    ET.SubElement(ambient_part, 'Image', resource=ambient)
    ET.SubElement(ambient_part, 'Variant', mode="AMBIENT", target="alpha", value="255")
    ET.SubElement(ambient_part, 'Transform', target="scaleX", value=f"[CONFIGURATION.{config}] == {index} ? 1 : 0")
    ET.SubElement(ambient_part, 'Transform', target="scaleY", value=f"[CONFIGURATION.{config}] == {index} ? 1 : 0")
    
    return [active_part, ambient_part]

def main():
    if len(sys.argv) < 3:
        print("Usage: generate_watchface.py <template_path> <output_path>")
        return

    template_path = sys.argv[1]
    output_path = sys.argv[2]
    
    if not os.path.exists(template_path):
        print(f"Error: {template_path} not found.")
        sys.exit(1)

    # Parse the template XML
    tree = ET.parse(template_path)
    root = tree.getroot()

    # We need to find all parents that contain <HAND> tags
    # Since we are modifying the list as we iterate, we'll collect parents first
    # Or just iterate through the Groups where we know HAND tags live.
    for group in root.findall(".//Group"):
        # Find all HAND elements in this group
        hand_elements = group.findall("HAND")
        for hand in hand_elements:
            # Generate replacement elements
            new_elements = create_hand_elements(hand)
            
            # Find the index of the HAND tag in the parent group
            # Convert to list to find the element
            children = list(group)
            idx = children.index(hand)
            
            # Remove the tag and insert the new ones
            group.remove(hand)
            for i, new_el in enumerate(new_elements):
                group.insert(idx + i, new_el)

    # Write the result (using ET.indent for pretty printing if available, else standard write)
    if hasattr(ET, 'indent'):
        ET.indent(tree, space="    ", level=0)
    
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    tree.write(output_path, encoding='utf-8', xml_declaration=True)
    print(f"Success! Properly parsed XML and generated {output_path}")

if __name__ == "__main__":
    main()
