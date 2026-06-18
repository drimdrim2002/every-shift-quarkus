import re

def fix_provider(file_path):
    with open(file_path, 'r') as f:
        content = f.read()

    # Update SOFT_LEVELS to 4
    content = re.sub(r'private static final int SOFT_LEVELS = \d+;', 'private static final int SOFT_LEVELS = 4;', content)
    
    # Remove SOFT_NIGHT_48H_REST_INDEX and shift others
    content = re.sub(r'\s*private static final int SOFT_NIGHT_48H_REST_INDEX = 0;', '', content)
    content = re.sub(r'private static final int SOFT_NIGHT_32H_REST_INDEX = 1;', 'private static final int SOFT_NIGHT_32H_REST_INDEX = 0;', content)
    content = re.sub(r'private static final int SOFT_UNDESIRED_INDEX = 2;', 'private static final int SOFT_UNDESIRED_INDEX = 1;', content)
    content = re.sub(r'private static final int SOFT_FAIRNESS_INDEX = 3;', 'private static final int SOFT_FAIRNESS_INDEX = 2;', content)
    content = re.sub(r'private static final int SOFT_DESIRED_INDEX = 4;', 'private static final int SOFT_DESIRED_INDEX = 3;', content)
    
    # Remove ONE_SOFT_NIGHT_48H_REST (if still present)
    content = re.sub(r'\s*private static final BendableScore ONE_SOFT_NIGHT_48H_REST.*?;', '', content, flags=re.DOTALL)
    
    with open(file_path, 'w') as f:
        f.write(content)

def fix_tests(file_path):
    with open(file_path, 'r') as f:
        content = f.read()
        
    def replacer(match):
        # original array has 6 elements
        elements = [e.strip() for e in match.group(1).split(',')]
        if len(elements) == 6:
            # We keep index 1, 2, 4, 5
            # elements[0] = Night48
            # elements[1] = Night32
            # elements[2] = Undesired
            # elements[3] = 3Consec (was 0 anyway)
            # elements[4] = Fairness
            # elements[5] = Desired
            new_elements = [elements[1], elements[2], elements[4], elements[5]]
            return 'new int[] { ' + ', '.join(new_elements) + ' }'
        return match.group(0)
    
    # Match new int[] { ... } inside new int[] { ... } of scores(...)
    content = re.sub(r'new int\[\]\s*\{\s*([^}]*?)\s*\}', replacer, content)

    # remove minimizeThreeConsecutiveNightShifts tests (if any still present)
    # Actually, we will just let tests fail if they test deleted soft constraints, but we'll remove the ones we know about.
    # Since we did `git restore`, the tests are back. Let's remove them properly.
    
    # Remove minimizeThreeConsecutiveNightShifts_PenalizesOneWindow
    content = re.sub(r'\s*@Test\s*void minimizeThreeConsecutiveNightShifts_PenalizesOneWindow\(\) \{.*?\n    \}', '', content, flags=re.DOTALL)
    content = re.sub(r'\s*@Test\s*void minimizeThreeConsecutiveNightShifts_NoThreeConsecutive\(\) \{.*?\n    \}', '', content, flags=re.DOTALL)
    content = re.sub(r'\s*@Test\s*void minimizeThreeConsecutiveNightShifts_FourConsecutiveCreatesTwoWindows\(\) \{.*?\n    \}', '', content, flags=re.DOTALL)
    
    with open(file_path, 'w') as f:
        f.write(content)

fix_provider('src/main/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProvider.java')
fix_tests('src/test/java/org/acme/solver/algorithm/EmployeeSchedulingConstraintProviderTest.java')
print("Done")
